#!/usr/bin/env python3
"""Release metadata and narrowly scoped GitHub/Central gates (standard library only).

`check` and `preflight` are read-only. Only `reserve-tag` and `create-release`
write to GitHub; neither command updates or deletes an existing reference/release.
Maven publication itself is performed by Gradle, not by this script.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from verify_publications import verify_repository


REPOSITORY = "sya-ri/ktAdvancements"
WORKFLOW = ".github/workflows/game-test.yml"
CENTRAL = "https://repo.maven.apache.org/maven2/"
ROOT = Path(__file__).resolve().parents[2]
VERSION = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)")
SHA = re.compile(r"[0-9a-f]{40}")
RUNTIME = re.compile(r"v\d+_\d+(?:_\d+)?")
DOCS = ("README.md", "skills/ktadvancements/references/ktadvancements-reference.md")
MODES = ("publish", "finish-release", "retry-publish")


class ReleaseError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def valid_version(version):
    require(VERSION.fullmatch(version) is not None, f"Not a stable release version: {version!r}")
    return version


def valid_sha(sha):
    require(SHA.fullmatch(sha) is not None, "Expected a full lowercase 40-character commit SHA")
    return sha


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, check=False)
    require(result.returncode == 0, f"git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def source(root, path, sha=None):
    return git(root, "show", f"{valid_sha(sha)}:{path}") if sha else (root / path).read_text(encoding="utf-8")


def runtime_names(root=ROOT, sha=None):
    names = git(root, "ls-tree", "--name-only", f"{valid_sha(sha)}:runtime").splitlines() if sha else (
        item.name for item in (root / "runtime").iterdir() if item.is_dir()
    )
    result = sorted((name for name in names if RUNTIME.fullmatch(name)), key=lambda name: tuple(map(int, name[1:].split("_"))))
    require(bool(result), "No runtime directories found")
    return result


@dataclass(frozen=True)
class Metadata:
    version: str
    notes: str

    @property
    def tag(self):
        return f"v{self.version}"


def read_metadata(root=ROOT, sha=None):
    versions = re.findall(r'^version\s*=\s*"([^"]+)"\s*$', source(root, "build.gradle.kts", sha), re.MULTILINE)
    require(len(versions) == 1, "build.gradle.kts must contain one literal root version")
    version = valid_version(versions[0])
    changelog = source(root, "CHANGELOG.md", sha)
    headers = list(re.finditer(r"^## (.+)$", changelog, re.MULTILINE))
    matches = [index for index, header in enumerate(headers) if header.group(1).strip() == f"v{version}"]
    require(matches == [0], f"CHANGELOG.md must start with exactly one ## v{version} entry")
    end = headers[1].start() if len(headers) > 1 else len(changelog)
    notes = changelog[headers[0].end():end].strip()
    require(bool(re.search(r"^- \S", notes, re.MULTILINE)), "Release notes must contain actual changes")
    for path in DOCS:
        text = source(root, path, sha)
        coordinates = re.findall(r"dev\.s7a:ktAdvancements-[\w-]+:([^\s\"'`)]+)", text)
        require(bool(coordinates), f"No dependency examples in {path}")
        require(all(value.split(":")[0] == version for value in coordinates), f"Dependency versions in {path} do not match {version}")
        require("mavenCentral()" in text and "central.sonatype.com/repository/maven-snapshots" not in text,
                f"Stable dependency examples in {path} must use Maven Central")
    return Metadata(version, notes)


class GitHub:
    def __init__(self, token):
        require(bool(token), "GH_TOKEN is required for GitHub release checks")
        self.token = token

    def request(self, path, data=None, missing_ok=False):
        url = f"https://api.github.com/repos/{REPOSITORY}/{path}"
        request = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "ktAdvancements-release",
        })
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok and data is None:
                return None
            # Do not retry POST requests: an uncertain response may already have created the object.
            raise ReleaseError(f"GitHub {request.get_method()} {path}: HTTP {error.code}; inspect GitHub before retrying") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise ReleaseError(f"GitHub {request.get_method()} {path} failed; no automatic retry") from error

    def tag_sha(self, version):
        tag = self.request(f"git/ref/tags/v{valid_version(version)}", missing_ok=True)
        if tag is None:
            return None
        obj = tag["object"]
        for _ in range(5):
            if obj["type"] == "commit":
                return valid_sha(obj["sha"])
            require(obj["type"] == "tag", "Release tag does not point to a commit")
            obj = self.request(f"git/tags/{valid_sha(obj['sha'])}")["object"]
        raise ReleaseError("Too many nested annotated tags")

    def master_sha(self):
        return valid_sha(self.request("git/ref/heads/master")["object"]["sha"])

    def assert_on_master(self, sha):
        comparison = self.request(f"compare/{valid_sha(sha)}...master")
        require(comparison["status"] in ("ahead", "identical"), "Release commit is not in master history")


def validate_run(run, sha):
    require(run.get("head_sha") == sha and run.get("head_branch") == "master" and run.get("event") == "push",
            "Game-test run must be a push to master at the exact release commit")
    require(run.get("head_repository", {}).get("full_name") == REPOSITORY, "Game-test run belongs to another repository")
    require(run.get("path") == WORKFLOW, "Unexpected game-test workflow path")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Game-test run has not completed successfully")


def successful_ci(github, sha, runtimes):
    query = urllib.parse.urlencode({"branch": "master", "event": "push", "head_sha": sha, "per_page": 100})
    runs = github.request(f"actions/workflows/game-test.yml/runs?{query}")["workflow_runs"]
    require(bool(runs), "No master game-test run found for the release commit")
    run = max(runs, key=lambda value: value["id"])
    validate_run(run, sha)
    jobs = []
    page = 1
    while True:
        batch = github.request(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100&page={page}")["jobs"]
        jobs.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    expected = {"Compile all runtimes"} | {
        "Minecraft " + name[1:].replace("_", ".") for name in runtimes
    }
    for name in expected:
        matches = [job for job in jobs if job["name"] == name]
        require(len(matches) == 1 and matches[0].get("conclusion") == "success", f"Missing or unsuccessful CI job: {name}")
    compile_job = next(job for job in jobs if job["name"] == "Compile all runtimes")
    for name in ("Test release tooling", "Stage signed release publications", "Validate signed release publications"):
        require(any(step.get("name") == name and step.get("conclusion") == "success" for step in compile_job.get("steps", [])),
                f"Missing or unsuccessful publication check: {name}")
    return str(run["id"])


def trigger(event_name, event, ref, repository):
    require(repository == REPOSITORY, "Releases can only run in sya-ri/ktAdvancements")
    if event_name == "workflow_run":
        run = event["workflow_run"]
        sha = valid_sha(run["head_sha"])
        validate_run(run, sha)
        return "publish", None, sha
    require(event_name == "workflow_dispatch" and ref == "refs/heads/master", "Manual releases must run from master")
    inputs = event.get("inputs", {})
    mode = inputs.get("mode", "publish")
    require(mode in MODES, "Unknown release mode")
    return mode, valid_version(inputs.get("version", "")), None


def preflight(github, event_name, event, ref, repository, root=ROOT):
    mode, requested, triggered_sha = trigger(event_name, event, ref, repository)
    master = github.master_sha()
    if triggered_sha is not None and triggered_sha != master:
        return {"skip": "true", "reason": "A newer master commit exists; its CI will gate the release."}
    sha = master if mode == "publish" else github.tag_sha(requested)
    require(sha is not None, "Recovery requires an existing version tag")
    metadata = read_metadata(root, sha)
    require(requested is None or requested == metadata.version, "Requested version does not match the selected source commit")
    existing = github.request(f"releases/tags/{metadata.tag}", missing_ok=True)
    tag_sha = github.tag_sha(metadata.version)
    if existing is not None:
        require(not existing["draft"] and not existing["prerelease"], "Existing draft/prerelease requires manual inspection")
        require(existing["tag_name"] == metadata.tag and tag_sha is not None, "Existing release has no matching tag")
        require(read_metadata(root, tag_sha).version == metadata.version, "Existing release tag points to another source version")
        github.assert_on_master(tag_sha)
        verify_published_commit(metadata.version, tag_sha)
        return {"skip": "true", "reason": f"{metadata.tag} is already released; nothing will be republished."}
    if mode == "publish":
        require(tag_sha is None, "Tag already reserved: inspect Central, then use finish-release or retry-publish; never move/delete the tag")
    else:
        require(tag_sha == sha, "Recovery tag changed during preflight")
        github.assert_on_master(sha)
    run_id = successful_ci(github, sha, runtime_names(root, sha))
    return {"skip": "false", "sha": sha, "version": metadata.version, "tag": metadata.tag, "mode": mode, "ci_run": run_id}


def publication_ids(root=ROOT):
    return ["ktAdvancements-api", "ktAdvancements-runtime", "ktAdvancements-runtime-mojang",
            "ktAdvancements-store-mysql", "ktAdvancements-store-sqlite"] + [
        f"ktAdvancements-runtime-{name}" for name in runtime_names(root)
    ]


def central_get(path):
    request = urllib.request.Request(CENTRAL + path, headers={"User-Agent": "ktAdvancements-release"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise ReleaseError(f"Central returned HTTP {error.code} for {path}; not proof of absence") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise ReleaseError(f"Central could not be checked for {path}; not proof of absence") from error


def central_state(version, root=ROOT, fetch=central_get):
    paths = [f"dev/s7a/{name}/{version}/{name}-{version}.pom" for name in publication_ids(root)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        present = [result is not None for result in pool.map(fetch, paths)]
    if all(present):
        return "published"
    if not any(present):
        return "absent"
    raise ReleaseError("Central contains only part of the release; wait/inspect Portal, do not upload again")


def verify_published_commit(version, sha):
    # target_commitish may be a branch and is ignored by GitHub when a tag already exists.
    # The immutable Central POM is the canonical binding for a completed release.
    artifact = "ktAdvancements-api"
    content = central_get(f"dev/s7a/{artifact}/{version}/{artifact}-{version}.pom")
    require(content is not None, "Existing GitHub Release has no public API POM on Central")
    pom = ET.fromstring(content)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for field, value in (("groupId", "dev.s7a"), ("artifactId", artifact), ("version", version), ("scm/tag", sha)):
        path = "/".join("m:" + part for part in field.split("/"))
        require(pom.findtext(path, namespaces=ns) == value,
                "Existing release/tag binding does not match the published API POM; inspect before continuing")


def checked_target(github, version, sha, root=ROOT):
    valid_sha(sha)
    valid_version(version)
    require(git(root, "rev-parse", "HEAD") == sha, "Checkout is not the exact release commit")
    require(not git(root, "status", "--porcelain", "--untracked-files=normal"), "Release checkout has uncommitted files")
    metadata = read_metadata(root)
    require(metadata.version == version, "Checkout version changed")
    github.assert_on_master(sha)
    successful_ci(github, sha, runtime_names(root))
    return metadata


def reserve_tag(github, version, sha, mode, root=ROOT):
    metadata = checked_target(github, version, sha, root)
    require(mode in ("publish", "retry-publish"), "finish-release must never upload")
    require(github.request(f"releases/tags/{metadata.tag}", missing_ok=True) is None, "GitHub Release already exists")
    require(central_state(version, root) == "absent", "Version already exists on Central; use finish-release")
    current_tag = github.tag_sha(version)
    if mode == "publish":
        require(current_tag is None, "Tag already exists; use explicit recovery mode")
        require(github.master_sha() == sha, "Master advanced before publication; wait for the new commit's CI")
        github.request("git/refs", {"ref": f"refs/tags/{metadata.tag}", "sha": sha})
    else:
        # Explicit operator action only: public 404s cannot detect an in-flight Portal deployment.
        require(current_tag == sha, "Retry must use the originally reserved tag commit")
    require(github.tag_sha(version) == sha, "Reserved tag does not match the tested commit")
    print(f"Reserved {metadata.tag} at {sha}; do not move/delete this tag if publishing fails.")


def verify_signatures(repository):
    artifacts = sorted(path for path in repository.rglob("*") if path.suffix in (".pom", ".jar", ".module"))
    require(bool(artifacts), "No artifacts to verify")
    for path in artifacts:
        result = subprocess.run(["gpg", "--batch", "--verify", str(path) + ".asc", str(path)], capture_output=True, check=False)
        require(result.returncode == 0, f"Invalid or untrusted-key signature: {path.relative_to(repository)}")
    print(f"Verified {len(artifacts)} detached signatures with the configured keyring.")


def verify_scm(repository, sha):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    poms = list(repository.rglob("*.pom"))
    require(bool(poms), "No POMs to verify")
    for path in poms:
        require(ET.parse(path).findtext("m:scm/m:tag", namespaces=ns) == sha,
                f"POM does not identify the exact release commit: {path.name}")


def download_central(local_repository, destination, sha, wait_seconds):
    # The signed, validated local repository defines exactly which artifacts must be public.
    paths = sorted(path.relative_to(local_repository).as_posix() for path in local_repository.rglob("*")
                   if path.is_file() and (path.suffix in (".pom", ".jar", ".module") or path.name.endswith((".pom.asc", ".jar.asc", ".module.asc"))))
    require(bool(paths), "Local publication manifest is empty")
    require(not destination.exists(), "Central verification directory must be new (avoid stale artifacts)")
    destination.mkdir(parents=True)
    deadline = time.monotonic() + wait_seconds
    pending = paths
    while pending:
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(central_get, pending))
        missing = []
        for path, content in zip(pending, results):
            if content is None:
                missing.append(path)
                continue
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        pending = missing
        if pending:
            require(time.monotonic() < deadline, f"Central visibility timeout: {len(pending)} files still missing; use finish-release later")
            print(f"Waiting for {len(pending)} Central files to become public...", flush=True)
            time.sleep(min(15, max(0, deadline - time.monotonic())))
    verify_scm(destination, sha)
    verify_signatures(destination)


def create_release(github, version, sha, root=ROOT):
    metadata = checked_target(github, version, sha, root)
    require(github.tag_sha(version) == sha, "Tag moved or disappeared; refusing to create a release")
    require(central_state(version, root) == "published", "All Central publications must be available first")
    require(github.request(f"releases/tags/{metadata.tag}", missing_ok=True) is None, "GitHub Release already exists; it will not be overwritten")
    notes = metadata.notes + f"\n\nMaven Central: `dev.s7a:ktAdvancements-api:{version}`. "
    notes += f"See the [installation and runtime options](https://github.com/{REPOSITORY}/blob/{metadata.tag}/README.md#installation).\n"
    # The tag already pins the exact commit. An explicit older target_commitish can require
    # workflows:write after workflow changes, which GITHUB_TOKEN cannot grant for recovery.
    release = github.request("releases", {"tag_name": metadata.tag, "name": metadata.tag,
                                         "body": notes, "draft": False, "prerelease": False, "make_latest": "legacy"})
    print(release["html_url"])


def outputs(values):
    print(json.dumps(values, indent=2))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            for key, value in values.items():
                require("\n" not in str(value), "Invalid multiline output")
                stream.write(f"{key}={value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "preflight", "reserve-tag", "verify-signatures", "verify-central", "create-release"))
    parser.add_argument("--version")
    parser.add_argument("--sha")
    parser.add_argument("--mode", choices=MODES, default="publish")
    parser.add_argument("--repository", type=Path, default=ROOT / "build/release-repository")
    parser.add_argument("--destination", type=Path, default=ROOT / "build/central-verification")
    parser.add_argument("--wait-seconds", type=int, default=1800)
    args = parser.parse_args()
    if args.command == "check":
        metadata = read_metadata()
        outputs({"version": metadata.version, "tag": metadata.tag})
    elif args.command == "verify-signatures":
        if args.sha:
            verify_scm(args.repository, valid_sha(args.sha))
        verify_signatures(args.repository)
    elif args.command == "verify-central":
        download_central(args.repository, args.destination, valid_sha(args.sha or ""), args.wait_seconds)
    else:
        github = GitHub(os.environ.get("GH_TOKEN"))
        if args.command == "preflight":
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
            outputs(preflight(github, os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_REF"], os.environ["GITHUB_REPOSITORY"]))
        else:
            version, sha = valid_version(args.version or ""), valid_sha(args.sha or "")
            if args.command == "reserve-tag":
                reserve_tag(github, version, sha, args.mode)
            else:
                verify_repository(args.destination, version, require_signatures=True)
                verify_scm(args.destination, sha)
                verify_signatures(args.destination)
                create_release(github, version, sha)


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Release check failed: {error}", file=sys.stderr)
        sys.exit(1)
