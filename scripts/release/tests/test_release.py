"""Release guards with synthetic metadata and mocked GitHub/Central responses.

Every test blocks real HTTP and subprocess calls. No credentials, Git repositories,
Maven uploads, or GitHub mutations are needed to exercise the failure paths.
"""

from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.parse


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release


VERSION = "1.0.0"
SHA = "a" * 40
OTHER_SHA = "b" * 40
RUNTIMES = ["v1_21_11", "v26_2"]
PUBLICATION_STEPS = (
    "Test release tooling",
    "Stage signed release publications",
    "Validate signed release publications",
)
METADATA = release.Metadata(VERSION, "### Added\n\n- Initial stable release.")


def workflow_run(run_id=10, **changes):
    result = {
        "id": run_id,
        "head_sha": SHA,
        "head_branch": "master",
        "event": "push",
        "head_repository": {"full_name": release.REPOSITORY},
        "path": release.WORKFLOW,
        "status": "completed",
        "conclusion": "success",
    }
    result.update(changes)
    return result


def workflow_jobs(runtimes=RUNTIMES):
    return [{
        "name": "Compile all runtimes",
        "conclusion": "success",
        "steps": [{"name": name, "conclusion": "success"} for name in PUBLICATION_STEPS],
    }] + [{
        "name": "Minecraft " + runtime[1:].replace("_", "."),
        "conclusion": "success",
    } for runtime in runtimes]


def existing_release(**changes):
    result = {"tag_name": "v1.0.0", "target_commitish": SHA, "draft": False, "prerelease": False}
    result.update(changes)
    return result


def http_error(code):
    error = urllib.error.HTTPError("https://example.invalid/fixture", code, "fixture", {}, io.BytesIO())
    error.close()
    return error


def api_pom(sha=SHA, version=VERSION, group="dev.s7a", artifact="ktAdvancements-api"):
    return (
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        f"<groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>"
        f"<scm><tag>{sha}</tag></scm></project>"
    ).encode()


class IsolatedTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ktadvancements-release-guards-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.network = self.enterContext(mock.patch.object(
            release.urllib.request, "urlopen", side_effect=AssertionError("Unexpected real HTTP request"),
        ))
        self.commands = self.enterContext(mock.patch.object(
            release.subprocess, "run", side_effect=AssertionError("Unexpected real subprocess"),
        ))

    def patch_release(self, name, **kwargs):
        return self.enterContext(mock.patch.object(release, name, **kwargs))

    def github(self):
        github = mock.Mock(spec=release.GitHub)
        github.master_sha.return_value = SHA
        github.tag_sha.return_value = None
        github.request.return_value = None
        return github

    def write(self, path, contents):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
        return target

    def assert_no_writes(self, github):
        for call in github.request.call_args_list:
            data = call.args[1] if len(call.args) > 1 else call.kwargs.get("data")
            self.assertIsNone(data, f"Unexpected GitHub write: {call}")


class MetadataTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.write("build.gradle.kts", 'plugins { kotlin("jvm") }\nversion = "1.0.0"\n')
        self.changelog = "# Changelog\n\n## v1.0.0\n\n### Added\n\n- New release.\n\n## v0.9.0\n\n- Old release.\n"
        self.write("CHANGELOG.md", self.changelog)
        self.documentation = (
            'repositories { mavenCentral() }\n'
            'implementation("dev.s7a:ktAdvancements-api:1.0.0")\n'
            'runtimeOnly("dev.s7a:ktAdvancements-runtime-v1_21_11:1.0.0:mojang-mapped")\n'
        )
        for path in release.DOCS:
            self.write(path, self.documentation)

    def test_extracts_only_current_release_notes(self):
        metadata = release.read_metadata(self.root)
        self.assertEqual(VERSION, metadata.version)
        self.assertEqual("v1.0.0", metadata.tag)
        self.assertEqual("### Added\n\n- New release.", metadata.notes)
        self.assertNotIn("Old release", metadata.notes)

    def test_stable_numeric_versions_only(self):
        for version in ("0.0.0", "1.0.0", "12.34.567"):
            with self.subTest(version=version):
                self.assertEqual(version, release.valid_version(version))
        for version in ("", "v1.0.0", "1.0", "01.0.0", "1.00.0", "1.0.0-SNAPSHOT", "1.0.0-rc.1", "1.0.0+build", "1.0.0\n", "../1.0.0"):
            with self.subTest(version=version), self.assertRaises(release.ReleaseError):
                release.valid_version(version)

    def test_requires_full_lowercase_commit_sha(self):
        self.assertEqual(SHA, release.valid_sha(SHA))
        for value in ("", "a" * 39, "a" * 41, "A" * 40, "g" * 40, SHA + "\n", "master", "--help"):
            with self.subTest(sha=value), self.assertRaises(release.ReleaseError):
                release.valid_sha(value)

    def test_requires_one_literal_root_version(self):
        for contents in ('version = providers.gradleProperty("version")', 'version = "1.0.0"\nversion = "1.0.0"', 'version = "1.0.0-SNAPSHOT"'):
            with self.subTest(contents=contents):
                self.write("build.gradle.kts", contents)
                with self.assertRaises(release.ReleaseError):
                    release.read_metadata(self.root)

    def test_requires_current_changelog_entry_first_and_unique(self):
        for changelog in (
            "# Changelog\n",
            "# Changelog\n\n## v0.9.0\n\n- Old.\n\n## v1.0.0\n\n- New.\n",
            "# Changelog\n\n## v1.0.0\n\n- New.\n\n## v1.0.0\n\n- Duplicate.\n",
            "# Changelog\n\n## v1.0.0\n\nTBD\n",
        ):
            with self.subTest(changelog=changelog):
                self.write("CHANGELOG.md", changelog)
                with self.assertRaises(release.ReleaseError):
                    release.read_metadata(self.root)

    def test_each_dependency_document_must_match_release(self):
        for path in release.DOCS:
            with self.subTest(path=path):
                self.write(path, self.documentation.replace(":1.0.0", ":0.9.0"))
                with self.assertRaisesRegex(release.ReleaseError, "Dependency versions"):
                    release.read_metadata(self.root)
                self.write(path, self.documentation)

    def test_stable_documentation_requires_central_and_dependency_examples(self):
        for text in (
            'repositories { mavenCentral() }\n',
            self.documentation.replace("mavenCentral()", "mavenLocal()"),
            self.documentation + 'maven("https://central.sonatype.com/repository/maven-snapshots/")\n',
        ):
            with self.subTest(text=text):
                self.write("README.md", text)
                with self.assertRaises(release.ReleaseError):
                    release.read_metadata(self.root)

    def test_unrelated_dependency_version_is_not_changed(self):
        self.write("README.md", self.documentation + 'compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")\n')
        self.assertEqual(VERSION, release.read_metadata(self.root).version)

    def test_reads_commit_metadata_from_git_not_working_files(self):
        git = self.patch_release("git", return_value="committed content")
        self.assertEqual("committed content", release.source(self.root, "README.md", SHA))
        git.assert_called_once_with(self.root, "show", f"{SHA}:README.md")

    def test_runtime_detection_uses_numeric_order_and_ignores_files(self):
        for name in ("v26_1_1", "v1_10", "v26_1", "v1_9", "not-a-runtime"):
            (self.root / "runtime" / name).mkdir(parents=True)
        self.write("runtime/v99_1", "not a directory")
        self.assertEqual(["v1_9", "v1_10", "v26_1", "v26_1_1"], release.runtime_names(self.root))

    def test_commit_runtime_detection_uses_selected_tree(self):
        git = self.patch_release("git", return_value="v26_2\nv1_21_11\nbuild.gradle.kts\n")
        self.assertEqual(RUNTIMES, release.runtime_names(self.root, SHA))
        git.assert_called_once_with(self.root, "ls-tree", "--name-only", f"{SHA}:runtime")


class GitHubHttpTests(IsolatedTests):
    def test_get_and_post_use_expected_http_method_and_payload(self):
        for data, method in ((None, "GET"), ({"ref": "refs/tags/v1.0.0", "sha": SHA}, "POST")):
            with self.subTest(method=method):
                self.network.reset_mock()
                self.network.side_effect = None
                self.network.return_value = io.BytesIO(b'{"ok":true}')
                self.assertEqual({"ok": True}, release.GitHub("fixture-token").request("git/refs", data))
                request = self.network.call_args.args[0]
                self.assertEqual(method, request.get_method())
                self.assertEqual(data, json.loads(request.data) if request.data is not None else None)
                self.assertEqual("https://api.github.com/repos/sya-ri/ktAdvancements/git/refs", request.full_url)
                self.network.assert_called_once()

    def test_only_optional_get_404_means_missing(self):
        self.network.side_effect = http_error(404)
        github = release.GitHub("fixture-token")
        self.assertIsNone(github.request("releases/tags/v1.0.0", missing_ok=True))
        for data, missing_ok in ((None, False), ({"sha": SHA}, True)):
            with self.subTest(data=data, missing_ok=missing_ok):
                self.network.reset_mock()
                with self.assertRaisesRegex(release.ReleaseError, "GitHub (GET|POST).*HTTP 404"):
                    github.request("git/refs", data=data, missing_ok=missing_ok)
                self.network.assert_called_once()

    def test_http_failures_do_not_become_absence_or_retry_posts(self):
        for code in (401, 403, 429, 500, 502, 503):
            for data, method in ((None, "GET"), ({"sha": SHA}, "POST")):
                with self.subTest(code=code, method=method):
                    self.network.reset_mock()
                    self.network.side_effect = http_error(code)
                    with self.assertRaisesRegex(release.ReleaseError, f"GitHub {method} .*HTTP {code}; inspect GitHub before retrying"):
                        release.GitHub("fixture-token").request("git/refs", data=data, missing_ok=True)
                    self.network.assert_called_once()

    def test_transport_failures_never_retry_including_uncertain_post(self):
        for error in (urllib.error.URLError("offline"), TimeoutError("timeout")):
            for data, method in ((None, "GET"), ({"sha": SHA}, "POST")):
                with self.subTest(error=type(error).__name__, method=method):
                    self.network.reset_mock()
                    self.network.side_effect = error
                    with self.assertRaisesRegex(release.ReleaseError, f"GitHub {method} .*no automatic retry"):
                        release.GitHub("fixture-token").request("git/refs", data=data, missing_ok=True)
                    self.network.assert_called_once()

    def test_missing_token_is_rejected_before_any_http_request(self):
        with self.assertRaisesRegex(release.ReleaseError, "GH_TOKEN"):
            release.GitHub("")
        self.network.assert_not_called()

    def test_lightweight_and_annotated_tag_resolve_to_commit(self):
        for responses in (
            [{"object": {"type": "commit", "sha": SHA}}],
            [{"object": {"type": "tag", "sha": OTHER_SHA}}, {"object": {"type": "commit", "sha": SHA}}],
        ):
            with self.subTest(responses=responses):
                github = release.GitHub("fixture-token")
                with mock.patch.object(github, "request", side_effect=responses):
                    self.assertEqual(SHA, github.tag_sha(VERSION))

    def test_missing_and_non_commit_tags(self):
        github = release.GitHub("fixture-token")
        with mock.patch.object(github, "request", return_value=None):
            self.assertIsNone(github.tag_sha(VERSION))
        with mock.patch.object(github, "request", return_value={"object": {"type": "tree", "sha": SHA}}):
            with self.assertRaisesRegex(release.ReleaseError, "does not point to a commit"):
                github.tag_sha(VERSION)

    def test_tag_cycle_is_bounded(self):
        github = release.GitHub("fixture-token")
        with mock.patch.object(github, "request", return_value={"object": {"type": "tag", "sha": SHA}}) as request:
            with self.assertRaisesRegex(release.ReleaseError, "Too many nested"):
                github.tag_sha(VERSION)
            self.assertEqual(6, request.call_count)

    def test_master_ancestry_direction(self):
        github = release.GitHub("fixture-token")
        for status in ("ahead", "identical", "behind", "diverged"):
            with self.subTest(status=status), mock.patch.object(github, "request", return_value={"status": status}) as request:
                if status in ("ahead", "identical"):
                    github.assert_on_master(SHA)
                else:
                    with self.assertRaisesRegex(release.ReleaseError, "not in master history"):
                        github.assert_on_master(SHA)
                request.assert_called_once_with(f"compare/{SHA}...master")


class CiTests(IsolatedTests):
    def configured_github(self, runs=None, jobs=None, pages=None):
        github = self.github()
        runs = [workflow_run()] if runs is None else runs
        jobs = workflow_jobs() if jobs is None else jobs

        def response(path, **kwargs):
            if path.startswith("actions/workflows/game-test.yml/runs?"):
                return {"workflow_runs": runs}
            if path.startswith("actions/runs/") and "/jobs?" in path:
                page = int(urllib.parse.parse_qs(path.split("?", 1)[1])["page"][0])
                return {"jobs": pages[page - 1] if pages is not None else jobs}
            raise AssertionError(f"Unexpected API path: {path}")

        github.request.side_effect = response
        return github

    def test_exact_successful_run_jobs_and_publication_steps(self):
        github = self.configured_github()
        self.assertEqual("10", release.successful_ci(github, SHA, RUNTIMES))
        query = urllib.parse.parse_qs(github.request.call_args_list[0].args[0].split("?", 1)[1])
        self.assertEqual({"branch": ["master"], "event": ["push"], "head_sha": [SHA], "per_page": ["100"]}, query)
        self.assertIn("actions/runs/10/jobs?filter=latest", github.request.call_args_list[1].args[0])
        self.assert_no_writes(github)

    def test_all_current_thirty_runtime_jobs_are_required(self):
        runtimes = release.runtime_names()
        self.assertEqual(30, len(runtimes))
        jobs = workflow_jobs(runtimes)
        self.assertEqual(31, len(jobs))
        self.assertEqual("10", release.successful_ci(self.configured_github(jobs=jobs), SHA, runtimes))
        with self.assertRaisesRegex(release.ReleaseError, "Missing or unsuccessful CI job"):
            release.successful_ci(self.configured_github(jobs=jobs[:-1]), SHA, runtimes)

    def test_latest_run_is_selected_even_if_api_order_differs(self):
        github = self.configured_github(runs=[workflow_run(20), workflow_run(1)])
        self.assertEqual("20", release.successful_ci(github, SHA, RUNTIMES))
        self.assertIn("actions/runs/20/jobs?", github.request.call_args_list[1].args[0])

    def test_failed_or_unfinished_latest_run_does_not_fall_back(self):
        for changes in ({"conclusion": "failure"}, {"status": "in_progress", "conclusion": None}, {"conclusion": "cancelled"}):
            with self.subTest(changes=changes):
                github = self.configured_github(runs=[workflow_run(1), workflow_run(2, **changes)])
                with self.assertRaisesRegex(release.ReleaseError, "not completed successfully"):
                    release.successful_ci(github, SHA, RUNTIMES)
                self.assertEqual(1, github.request.call_count)

    def test_no_matching_run_fails(self):
        with self.assertRaisesRegex(release.ReleaseError, "No master game-test run"):
            release.successful_ci(self.configured_github(runs=[]), SHA, RUNTIMES)

    def test_run_identity_must_match_source_commit_repository_and_workflow(self):
        for changes in (
            {"head_sha": OTHER_SHA}, {"head_branch": "feature"}, {"event": "pull_request"},
            {"head_repository": {"full_name": "someone/ktAdvancements"}}, {"path": ".github/workflows/other.yml"},
        ):
            with self.subTest(changes=changes), self.assertRaises(release.ReleaseError):
                release.successful_ci(self.configured_github(runs=[workflow_run(**changes)]), SHA, RUNTIMES)

    def test_missing_failed_or_duplicate_required_job_fails(self):
        for index in range(len(workflow_jobs())):
            for change in ("missing", "failure", "skipped", "duplicate"):
                with self.subTest(index=index, change=change):
                    jobs = workflow_jobs()
                    if change == "missing":
                        jobs.pop(index)
                    elif change == "duplicate":
                        jobs.append(dict(jobs[index]))
                    else:
                        jobs[index]["conclusion"] = change
                    with self.assertRaisesRegex(release.ReleaseError, "Missing or unsuccessful CI job"):
                        release.successful_ci(self.configured_github(jobs=jobs), SHA, RUNTIMES)

    def test_all_three_publication_steps_must_succeed(self):
        for index, name in enumerate(PUBLICATION_STEPS):
            for change in ("missing", "failure", "skipped"):
                with self.subTest(name=name, change=change):
                    jobs = workflow_jobs()
                    if change == "missing":
                        jobs[0]["steps"].pop(index)
                    else:
                        jobs[0]["steps"][index]["conclusion"] = change
                    with self.assertRaisesRegex(release.ReleaseError, "Missing or unsuccessful publication check"):
                        release.successful_ci(self.configured_github(jobs=jobs), SHA, RUNTIMES)

    def test_job_pagination_does_not_drop_later_required_jobs(self):
        extras = [{"name": f"Unrelated {index}", "conclusion": "success"} for index in range(100)]
        github = self.configured_github(pages=[extras, workflow_jobs()])
        self.assertEqual("10", release.successful_ci(github, SHA, RUNTIMES))
        self.assertIn("page=2", github.request.call_args_list[-1].args[0])


class TriggerTests(IsolatedTests):
    def test_completed_master_push_selects_exact_sha(self):
        self.assertEqual(("publish", None, SHA), release.trigger(
            "workflow_run", {"workflow_run": workflow_run()}, "refs/heads/master", release.REPOSITORY,
        ))

    def test_fork_pull_request_non_master_and_wrong_workflow_are_rejected(self):
        for changes in (
            {"event": "pull_request"}, {"head_branch": "feature"},
            {"head_repository": {"full_name": "someone/ktAdvancements"}},
            {"path": ".github/workflows/other.yml"}, {"conclusion": "failure"},
        ):
            with self.subTest(changes=changes), self.assertRaises(release.ReleaseError):
                release.trigger("workflow_run", {"workflow_run": workflow_run(**changes)}, "refs/heads/master", release.REPOSITORY)
        with self.assertRaisesRegex(release.ReleaseError, "only run in"):
            release.trigger("workflow_run", {"workflow_run": workflow_run()}, "refs/heads/master", "someone/ktAdvancements")

    def test_manual_modes_require_master_and_explicit_stable_version(self):
        for mode in release.MODES:
            with self.subTest(mode=mode):
                self.assertEqual((mode, VERSION, None), release.trigger(
                    "workflow_dispatch", {"inputs": {"mode": mode, "version": VERSION}}, "refs/heads/master", release.REPOSITORY,
                ))
        for ref in ("refs/heads/feature", "refs/tags/v1.0.0", "master"):
            with self.subTest(ref=ref), self.assertRaisesRegex(release.ReleaseError, "must run from master"):
                release.trigger("workflow_dispatch", {"inputs": {"version": VERSION}}, ref, release.REPOSITORY)
        for inputs in ({}, {"mode": "delete", "version": VERSION}, {"version": "1.0.0-SNAPSHOT"}):
            with self.subTest(inputs=inputs), self.assertRaises(release.ReleaseError):
                release.trigger("workflow_dispatch", {"inputs": inputs}, "refs/heads/master", release.REPOSITORY)

    def test_other_event_types_are_rejected(self):
        for event in ("push", "pull_request", "pull_request_target", "release"):
            with self.subTest(event=event), self.assertRaises(release.ReleaseError):
                release.trigger(event, {}, "refs/heads/master", release.REPOSITORY)


class PreflightTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.remote = self.github()
        self.metadata = self.patch_release("read_metadata", return_value=METADATA)
        self.runtimes = self.patch_release("runtime_names", return_value=RUNTIMES)
        self.ci = self.patch_release("successful_ci", return_value="25")
        self.central = self.patch_release("central_get", return_value=api_pom())

    def prepare(self, mode="publish", version=VERSION, automatic=False):
        event = {"workflow_run": workflow_run()} if automatic else {"inputs": {"mode": mode, "version": version}}
        return release.preflight(self.remote, "workflow_run" if automatic else "workflow_dispatch",
                                 event, "refs/heads/master", release.REPOSITORY, self.root)

    def test_stale_automatic_commit_is_noop_without_further_checks(self):
        self.remote.master_sha.return_value = OTHER_SHA
        self.assertEqual("true", self.prepare(automatic=True)["skip"])
        self.metadata.assert_not_called()
        self.ci.assert_not_called()
        self.remote.request.assert_not_called()
        self.central.assert_not_called()

    def test_fresh_release_preparation_is_read_only(self):
        self.assertEqual({"skip": "false", "sha": SHA, "version": VERSION, "tag": "v1.0.0", "mode": "publish", "ci_run": "25"}, self.prepare(automatic=True))
        self.metadata.assert_called_once_with(self.root, SHA)
        self.ci.assert_called_once_with(self.remote, SHA, RUNTIMES)
        self.central.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_manual_requested_version_must_match_source(self):
        with self.assertRaisesRegex(release.ReleaseError, "does not match"):
            self.prepare(version="1.1.0")
        self.ci.assert_not_called()

    def test_reserved_tag_stops_automatic_or_fresh_publish(self):
        self.remote.tag_sha.return_value = SHA
        with self.assertRaisesRegex(release.ReleaseError, "Tag already reserved"):
            self.prepare(automatic=True)
        self.ci.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_recovery_uses_tagged_commit_not_new_master_head(self):
        self.remote.master_sha.return_value = OTHER_SHA
        self.remote.tag_sha.return_value = SHA
        for mode in ("finish-release", "retry-publish"):
            with self.subTest(mode=mode):
                result = self.prepare(mode=mode)
                self.assertEqual(SHA, result["sha"])
                self.assertEqual(mode, result["mode"])
                self.remote.assert_on_master.assert_called_with(SHA)
                self.ci.assert_called_with(self.remote, SHA, RUNTIMES)
        self.assert_no_writes(self.remote)

    def test_recovery_requires_existing_unchanged_tag(self):
        with self.assertRaisesRegex(release.ReleaseError, "Recovery requires"):
            self.prepare(mode="finish-release")
        self.remote.tag_sha.side_effect = [SHA, OTHER_SHA]
        with self.assertRaisesRegex(release.ReleaseError, "tag changed"):
            self.prepare(mode="retry-publish")
        self.ci.assert_not_called()

    def test_completed_release_is_noop_on_later_same_version_master(self):
        self.remote.master_sha.return_value = OTHER_SHA
        self.remote.tag_sha.return_value = SHA
        # GitHub can return a branch here even though its existing tag is fixed.
        self.remote.request.return_value = existing_release(target_commitish="master")
        self.assertEqual("true", self.prepare()["skip"])
        self.remote.assert_on_master.assert_called_once_with(SHA)
        self.central.assert_called_once_with("dev/s7a/ktAdvancements-api/1.0.0/ktAdvancements-api-1.0.0.pom")
        self.ci.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_existing_draft_or_prerelease_is_not_completed_release(self):
        self.remote.tag_sha.return_value = SHA
        for field in ("draft", "prerelease"):
            with self.subTest(field=field):
                self.remote.request.return_value = existing_release(**{field: True})
                with self.assertRaisesRegex(release.ReleaseError, "draft/prerelease"):
                    self.prepare()

    def test_moved_or_deleted_release_tag_requires_inspection(self):
        for tag in (OTHER_SHA, None):
            with self.subTest(tag=tag):
                self.remote.tag_sha.return_value = tag
                self.remote.request.return_value = existing_release(target_commitish="master")
                with self.assertRaises(release.ReleaseError):
                    self.prepare()
        self.ci.assert_not_called()

    def test_existing_release_with_missing_or_wrong_public_api_is_not_complete(self):
        self.remote.tag_sha.return_value = SHA
        self.remote.request.return_value = existing_release()
        for pom in (None, api_pom(sha=OTHER_SHA), api_pom(version="0.9.0")):
            with self.subTest(pom=pom):
                self.central.return_value = pom
                with self.assertRaises(release.ReleaseError):
                    self.prepare()
        self.ci.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_existing_tag_cannot_point_to_other_source_version(self):
        self.remote.tag_sha.return_value = SHA
        self.remote.request.return_value = existing_release()
        self.metadata.side_effect = [METADATA, release.Metadata("0.9.0", "- Old.")]
        with self.assertRaisesRegex(release.ReleaseError, "another source version"):
            self.prepare()


class CentralTests(IsolatedTests):
    def test_public_get_returns_content_or_only_404_absence(self):
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(b"artifact")
        self.assertEqual(b"artifact", release.central_get("dev/s7a/fixture.pom"))
        self.assertEqual(release.CENTRAL + "dev/s7a/fixture.pom", self.network.call_args.args[0].full_url)
        self.network.side_effect = http_error(404)
        self.assertIsNone(release.central_get("dev/s7a/fixture.pom"))

    def test_http_or_transport_failure_is_not_absence(self):
        for error in [http_error(code) for code in (401, 403, 429, 500, 502, 503)] + [urllib.error.URLError("offline"), TimeoutError("timeout")]:
            with self.subTest(error=str(error)):
                self.network.reset_mock()
                self.network.side_effect = error
                with self.assertRaisesRegex(release.ReleaseError, "not proof of absence"):
                    release.central_get("dev/s7a/fixture.pom")
                self.network.assert_called_once()

    def test_complete_absence_and_complete_publication(self):
        self.patch_release("publication_ids", return_value=["ktAdvancements-api", "ktAdvancements-runtime"])
        for content, expected in ((None, "absent"), (b"pom", "published")):
            with self.subTest(expected=expected):
                fetch = mock.Mock(return_value=content)
                self.assertEqual(expected, release.central_state(VERSION, self.root, fetch=fetch))
                self.assertEqual(2, fetch.call_count)
                self.assertEqual({
                    "dev/s7a/ktAdvancements-api/1.0.0/ktAdvancements-api-1.0.0.pom",
                    "dev/s7a/ktAdvancements-runtime/1.0.0/ktAdvancements-runtime-1.0.0.pom",
                }, {call.args[0] for call in fetch.call_args_list})

    def test_partial_publication_fails_closed(self):
        self.patch_release("publication_ids", return_value=["ktAdvancements-api", "ktAdvancements-runtime"])
        with self.assertRaisesRegex(release.ReleaseError, "only part of the release"):
            release.central_state(VERSION, self.root, fetch=lambda path: b"pom" if "/ktAdvancements-api/" in path else None)

    def test_fetch_failure_propagates_not_absence(self):
        self.patch_release("publication_ids", return_value=["ktAdvancements-api"])
        with self.assertRaisesRegex(release.ReleaseError, "offline"):
            release.central_state(VERSION, self.root, fetch=mock.Mock(side_effect=release.ReleaseError("offline")))

    def test_published_commit_uses_exact_canonical_api_pom(self):
        fetch = self.patch_release("central_get", return_value=api_pom())
        release.verify_published_commit(VERSION, SHA)
        fetch.assert_called_once_with("dev/s7a/ktAdvancements-api/1.0.0/ktAdvancements-api-1.0.0.pom")

    def test_published_api_requires_correct_coordinates_and_scm_commit(self):
        fetch = self.patch_release("central_get")
        for changes in ({"group": "other"}, {"artifact": "other"}, {"version": "0.9.0"}, {"sha": OTHER_SHA}):
            with self.subTest(changes=changes):
                fetch.return_value = api_pom(**changes)
                with self.assertRaisesRegex(release.ReleaseError, "binding does not match"):
                    release.verify_published_commit(VERSION, SHA)

    def test_missing_or_malformed_published_api_fails_closed(self):
        fetch = self.patch_release("central_get")
        for contents in (None, b"not XML", b"<project/>", api_pom().replace(f"<tag>{SHA}</tag>".encode(), b"")):
            with self.subTest(contents=contents):
                fetch.return_value = contents
                with self.assertRaises((release.ReleaseError, release.ET.ParseError)):
                    release.verify_published_commit(VERSION, SHA)

    def test_published_api_transport_error_cannot_confirm_completed_release(self):
        self.network.side_effect = urllib.error.URLError("offline")
        with self.assertRaisesRegex(release.ReleaseError, "not proof of absence"):
            release.verify_published_commit(VERSION, SHA)


class TargetTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.remote = self.github()
        self.git = self.patch_release("git", side_effect=[SHA, ""])
        self.metadata = self.patch_release("read_metadata", return_value=METADATA)
        self.patch_release("runtime_names", return_value=RUNTIMES)
        self.ci = self.patch_release("successful_ci", return_value="25")

    def test_clean_exact_checkout_rechecks_ancestry_and_ci(self):
        self.assertEqual(METADATA, release.checked_target(self.remote, VERSION, SHA, self.root))
        self.assertIn(mock.call(self.root, "status", "--porcelain", "--untracked-files=normal"), self.git.call_args_list)
        self.remote.assert_on_master.assert_called_once_with(SHA)
        self.ci.assert_called_once_with(self.remote, SHA, RUNTIMES)

    def test_dirty_tracked_staged_and_untracked_sources_fail_before_ci(self):
        for status in (" M api/Example.kt", "M  build.gradle.kts", "?? runtime/Example.kt"):
            with self.subTest(status=status):
                self.git.side_effect = [SHA, status]
                with self.assertRaisesRegex(release.ReleaseError, "uncommitted files"):
                    release.checked_target(self.remote, VERSION, SHA, self.root)
        self.metadata.assert_not_called()
        self.ci.assert_not_called()

    def test_other_checkout_commit_is_rejected_before_metadata(self):
        self.git.side_effect = [OTHER_SHA]
        with self.assertRaisesRegex(release.ReleaseError, "not the exact release commit"):
            release.checked_target(self.remote, VERSION, SHA, self.root)
        self.metadata.assert_not_called()

    def test_source_version_change_is_rejected(self):
        self.metadata.return_value = release.Metadata("1.1.0", "- New.")
        with self.assertRaisesRegex(release.ReleaseError, "Checkout version changed"):
            release.checked_target(self.remote, VERSION, SHA, self.root)
        self.ci.assert_not_called()

    def test_failed_ci_recheck_stops_target_validation(self):
        self.ci.side_effect = release.ReleaseError("latest CI failed")
        with self.assertRaisesRegex(release.ReleaseError, "latest CI failed"):
            release.checked_target(self.remote, VERSION, SHA, self.root)
        self.assert_no_writes(self.remote)


class ReservationTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.remote = self.github()
        self.target = self.patch_release("checked_target", return_value=METADATA)
        self.central = self.patch_release("central_state", return_value="absent")

    def reserve(self, mode="publish"):
        release.reserve_tag(self.remote, VERSION, SHA, mode, self.root)

    def test_fresh_tag_is_created_once_at_exact_tested_commit(self):
        self.remote.tag_sha.side_effect = [None, SHA]
        self.reserve()
        writes = [call for call in self.remote.request.call_args_list if len(call.args) > 1]
        self.assertEqual([mock.call("git/refs", {"ref": "refs/tags/v1.0.0", "sha": SHA})], writes)
        self.target.assert_called_once_with(self.remote, VERSION, SHA, self.root)

    def test_existing_tag_is_never_overwritten(self):
        for tag in (SHA, OTHER_SHA):
            with self.subTest(tag=tag):
                self.remote.tag_sha.return_value = tag
                with self.assertRaisesRegex(release.ReleaseError, "Tag already exists"):
                    self.reserve()
        self.assert_no_writes(self.remote)

    def test_finish_release_never_reserves_or_uploads(self):
        with self.assertRaisesRegex(release.ReleaseError, "finish-release must never upload"):
            self.reserve("finish-release")
        self.remote.request.assert_not_called()
        self.central.assert_not_called()

    def test_explicit_retry_retains_original_tag_even_after_master_advances(self):
        self.remote.master_sha.return_value = OTHER_SHA
        self.remote.tag_sha.return_value = SHA
        self.reserve("retry-publish")
        self.remote.master_sha.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_retry_cannot_change_or_recreate_original_tag(self):
        for tag in (None, OTHER_SHA):
            with self.subTest(tag=tag):
                self.remote.tag_sha.return_value = tag
                with self.assertRaisesRegex(release.ReleaseError, "originally reserved tag"):
                    self.reserve("retry-publish")
        self.assert_no_writes(self.remote)

    def test_new_master_commit_blocks_new_tag(self):
        self.remote.master_sha.return_value = OTHER_SHA
        with self.assertRaisesRegex(release.ReleaseError, "Master advanced"):
            self.reserve()
        self.assert_no_writes(self.remote)

    def test_public_or_partial_central_deployment_prevents_new_tag(self):
        self.central.return_value = "published"
        with self.assertRaisesRegex(release.ReleaseError, "already exists on Central"):
            self.reserve()
        self.central.side_effect = release.ReleaseError("Central contains only part of the release")
        with self.assertRaisesRegex(release.ReleaseError, "only part of the release"):
            self.reserve()
        self.assert_no_writes(self.remote)

    def test_existing_release_stops_before_tag_or_central(self):
        self.remote.request.return_value = existing_release()
        with self.assertRaisesRegex(release.ReleaseError, "GitHub Release already exists"):
            self.reserve()
        self.central.assert_not_called()
        self.assert_no_writes(self.remote)

    def test_target_check_failure_blocks_all_writes(self):
        self.target.side_effect = release.ReleaseError("checkout is dirty")
        with self.assertRaisesRegex(release.ReleaseError, "checkout is dirty"):
            self.reserve()
        self.remote.request.assert_not_called()

    def test_tag_readback_mismatch_never_retries_or_overwrites(self):
        self.remote.tag_sha.side_effect = [None, OTHER_SHA]
        with self.assertRaisesRegex(release.ReleaseError, "Reserved tag does not match"):
            self.reserve()
        writes = [call for call in self.remote.request.call_args_list if len(call.args) > 1]
        self.assertEqual([mock.call("git/refs", {"ref": "refs/tags/v1.0.0", "sha": SHA})], writes)


class FinalizationTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.remote = self.github()
        self.remote.tag_sha.return_value = SHA
        self.patch_release("checked_target", return_value=METADATA)
        self.central = self.patch_release("central_state", return_value="published")

    def test_release_uses_exact_tag_commit_and_current_changelog(self):
        self.remote.request.side_effect = [None, {"html_url": "https://example.invalid/release"}]
        release.create_release(self.remote, VERSION, SHA, self.root)
        path, payload = self.remote.request.call_args.args
        self.assertEqual("releases", path)
        self.assertEqual("v1.0.0", payload["tag_name"])
        self.remote.tag_sha.assert_called_once_with(VERSION)
        self.assertNotIn("target_commitish", payload)
        self.assertFalse(payload["draft"])
        self.assertFalse(payload["prerelease"])
        self.assertEqual("legacy", payload["make_latest"])
        self.assertTrue(payload["body"].startswith(METADATA.notes))
        self.assertIn("dev.s7a:ktAdvancements-api:1.0.0", payload["body"])

    def test_moved_or_deleted_tag_prevents_release_creation(self):
        for tag in (None, OTHER_SHA):
            with self.subTest(tag=tag):
                self.remote.tag_sha.return_value = tag
                with self.assertRaisesRegex(release.ReleaseError, "Tag moved or disappeared"):
                    release.create_release(self.remote, VERSION, SHA, self.root)
        self.assert_no_writes(self.remote)

    def test_all_central_publications_must_exist(self):
        self.central.return_value = "absent"
        with self.assertRaisesRegex(release.ReleaseError, "must be available first"):
            release.create_release(self.remote, VERSION, SHA, self.root)
        self.assert_no_writes(self.remote)

    def test_existing_release_is_never_updated(self):
        self.remote.request.return_value = existing_release()
        with self.assertRaisesRegex(release.ReleaseError, "will not be overwritten"):
            release.create_release(self.remote, VERSION, SHA, self.root)
        self.assert_no_writes(self.remote)


class CliFinalizationTests(IsolatedTests):
    def setUp(self):
        super().setUp()
        self.destination = self.root / "central-verification"
        self.enterContext(mock.patch.object(sys, "argv", [
            "release.py", "create-release", "--version", VERSION, "--sha", SHA,
            "--destination", str(self.destination),
        ]))
        self.enterContext(mock.patch.dict(release.os.environ, {"GH_TOKEN": "fixture-token"}, clear=True))
        self.remote = self.github()
        self.patch_release("GitHub", return_value=self.remote)
        self.checks = mock.Mock()
        for name in ("verify_repository", "verify_scm", "verify_signatures", "create_release"):
            self.checks.attach_mock(self.patch_release(name), name)

    def test_cli_checks_complete_signed_repository_before_creating_release(self):
        release.main()
        self.assertEqual([
            mock.call.verify_repository(self.destination, VERSION, require_signatures=True),
            mock.call.verify_scm(self.destination, SHA),
            mock.call.verify_signatures(self.destination),
            mock.call.create_release(self.remote, VERSION, SHA),
        ], self.checks.mock_calls)

    def test_incomplete_repository_never_reaches_release_creation(self):
        self.checks.verify_repository.side_effect = ValueError("Missing publication")
        with self.assertRaisesRegex(ValueError, "Missing publication"):
            release.main()
        self.checks.verify_scm.assert_not_called()
        self.checks.verify_signatures.assert_not_called()
        self.checks.create_release.assert_not_called()
        self.assert_no_writes(self.remote)


if __name__ == "__main__":
    unittest.main()
