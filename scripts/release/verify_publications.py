#!/usr/bin/env python3
"""Check the complete, isolated Maven repository before a release is uploaded.

This is a read-only, standard-library check. --require-signatures checks detached
ASCII armor, not its cryptographic validity or the identity of the signing key.
The release workflow must additionally verify signatures with its trusted key.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path


GROUP = "dev.s7a"
API = "ktAdvancements-api"
STORES = {"ktAdvancements-store-mysql", "ktAdvancements-store-sqlite"}
AGGREGATES = {"ktAdvancements-runtime", "ktAdvancements-runtime-mojang"}
COMPONENT_PUBLICATIONS = {API, *STORES}
RUNTIME_PREFIX = "ktAdvancements-runtime-"
POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
NS = {"m": POM_NAMESPACE}
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
PROJECT_ROOT = Path(__file__).resolve().parents[2]


class ValidationError(ValueError):
    """A publication cannot safely be released."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def release_version(value: str) -> str:
    require(
        re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value) is not None,
        f"Expected a numeric release SemVer without a suffix, got {value!r}",
    )
    return value


def discover_runtimes(runtime_root: Path) -> dict[str, bool]:
    """Map runtime artifact IDs to whether they need a mojang-mapped JAR."""
    require(runtime_root.is_dir(), f"Missing runtime source directory: {runtime_root}")
    names = sorted(
        path.name
        for path in runtime_root.iterdir()
        if path.is_dir() and re.fullmatch(r"v[0-9]+_[0-9]+(?:_[0-9]+)?", path.name)
    )
    require(len(names) == 30, f"Expected exactly 30 runtime source directories, found {len(names)}")
    result = {}
    for name in names:
        parts = tuple(int(part) for part in name[1:].split("_"))
        minecraft_version = parts + (0,) * (3 - len(parts))
        result[RUNTIME_PREFIX + name] = minecraft_version < (26, 1, 0)
    require(sum(result.values()) == 26, "Expected 26 legacy and 4 unobfuscated runtimes")
    return result


def text(element: ET.Element, path: str, context: str, *, optional: bool = False) -> str:
    nodes = element.findall("/".join("m:" + part for part in path.split("/")), NS)
    require(len(nodes) <= 1, f"{context}: duplicate {path}")
    value = (nodes[0].text or "").strip() if nodes else ""
    require(optional or bool(value), f"{context}: missing {path}")
    return value


def fixed_dependency(group: str, artifact: str, version: str, expected: set[str], release: str, context: str) -> None:
    require(
        isinstance(group, str) and bool(group) and isinstance(artifact, str) and bool(artifact),
        f"{context}: missing dependency coordinates",
    )
    require(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", version) is not None
        and "SNAPSHOT" not in version.upper()
        and not re.match(r"^(?:latest|release)(?:$|[.-])", version, re.IGNORECASE),
        f"{context}: dependency {group}:{artifact} has a non-fixed release version {version!r}",
    )
    if group == GROUP or artifact.startswith("ktAdvancements-"):
        require(group == GROUP and artifact in expected, f"{context}: unexpected project dependency {group}:{artifact}")
        require(version == release, f"{context}: project dependency {artifact} must use version {release}, got {version}")
    require(
        group != "io.papermc" and not group.startswith("io.papermc.")
        and group != "com.destroystokyo.paper",
        f"{context}: Paper development dependencies must not be published",
    )


def validate_pom(path: Path, artifact: str, version: str, runtimes: dict[str, bool], expected: set[str]) -> None:
    root = ET.parse(path).getroot()
    context = path.name
    require(root.tag == f"{{{POM_NAMESPACE}}}project", f"{context}: not a Maven POM")
    require(root.find("m:parent", NS) is None, f"{context}: published POM must be self-contained (no parent)")
    for field, value in (("modelVersion", "4.0.0"), ("groupId", GROUP), ("artifactId", artifact), ("version", version)):
        require(text(root, field, context) == value, f"{context}: {field} must be {value}")
    for field in ("name", "description", "url", "scm/url", "scm/connection", "scm/developerConnection"):
        text(root, field, context)
    licenses = root.findall("m:licenses/m:license", NS)
    require(len(licenses) == 1, f"{context}: expected one MIT license")
    require(text(licenses[0], "name", context) in {"MIT", "MIT License", "The MIT License"}, f"{context}: license must be MIT")
    text(licenses[0], "url", context)
    developers = root.findall("m:developers/m:developer", NS)
    require(bool(developers), f"{context}: missing developer metadata")
    for developer in developers:
        for field in ("id", "name", "email"):
            text(developer, field, context)

    packaging = text(root, "packaging", context, optional=True) or "jar"
    require(packaging == ("pom" if artifact in AGGREGATES else "jar"), f"{context}: incorrect packaging {packaging!r}")
    dependencies = root.findall(".//m:dependency", NS)
    for dependency in dependencies:
        fixed_dependency(
            text(dependency, "groupId", context), text(dependency, "artifactId", context),
            text(dependency, "version", context), expected, version, context,
        )
    if artifact in runtimes:
        require(not dependencies, f"{context}: manual runtime POM must not contain dependencies")
    if artifact in STORES:
        api_dependencies = [
            dependency for dependency in root.findall("m:dependencies/m:dependency", NS)
            if text(dependency, "groupId", context) == GROUP
            and text(dependency, "artifactId", context) == API
        ]
        require(len(api_dependencies) == 1, f"{context}: store must depend on the API exactly once")
    if artifact in AGGREGATES:
        direct = root.findall("m:dependencies/m:dependency", NS)
        require(len(direct) == len(dependencies) == 30, f"{context}: aggregate must have exactly 30 direct runtime dependencies")
        seen = set()
        for dependency in direct:
            target = text(dependency, "artifactId", context)
            require(text(dependency, "groupId", context) == GROUP and target in runtimes, f"{context}: unexpected aggregate dependency {target}")
            require(target not in seen, f"{context}: duplicate aggregate dependency {target}")
            seen.add(target)
            classifier = text(dependency, "classifier", context, optional=True)
            wanted = "mojang-mapped" if artifact.endswith("-mojang") and runtimes[target] else ""
            require(classifier == wanted, f"{context}: {target} classifier must be {wanted!r}, got {classifier!r}")
            require((text(dependency, "scope", context, optional=True) or "compile") == "compile", f"{context}: {target} must have compile scope")
            require((text(dependency, "type", context, optional=True) or "jar") == "jar", f"{context}: {target} must reference a JAR")
            require(text(dependency, "optional", context, optional=True) in {"", "false"}, f"{context}: {target} must not be optional")
        require(seen == set(runtimes), f"{context}: missing aggregate runtime dependencies")


def validate_jar(path: Path, classifier: str, runtime_name: str | None = None) -> None:
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, f"{path.name}: corrupt JAR entry")
        names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
        require(len(names) == len(set(names)), f"{path.name}: duplicate JAR entries")
        if classifier == "sources":
            require(
                any(name.endswith((".kt", ".java")) and archive.read(name).strip() for name in names),
                f"{path.name}: sources JAR must contain non-empty Kotlin or Java source",
            )
        elif classifier == "javadoc":
            require(
                any(
                    re.search(rb"<html(?:\s|>)", archive.read(name), re.IGNORECASE)
                    and re.search(rb"<body(?:\s|>)", archive.read(name), re.IGNORECASE)
                    for name in names if name.endswith(".html")
                ),
                f"{path.name}: javadoc JAR must contain generated HTML documentation",
            )
        else:
            classes = [name for name in names if name.endswith(".class")]
            require(bool(classes), f"{path.name}: binary JAR has no classes")
            if runtime_name is not None:
                implementation = f"dev/s7a/ktAdvancements/runtime/{runtime_name}/KtAdvancementRuntimeImpl.class"
                require(implementation in classes, f"{path.name}: missing runtime implementation {implementation}")
                classes = [implementation]
            require(
                all(archive.read(name)[:4] == b"\xca\xfe\xba\xbe" for name in classes),
                f"{path.name}: invalid class file",
            )


def validate_module(path: Path, artifact: str, version: str, expected: set[str], jars: set[Path]) -> None:
    module = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(module, dict), f"{path.name}: invalid module metadata")
    require(module.get("formatVersion") in ("1.0", "1.1"), f"{path.name}: unsupported Gradle metadata format")
    component = module.get("component")
    require(isinstance(component, dict), f"{path.name}: missing module component")
    for field, value in (("group", GROUP), ("module", artifact), ("version", version)):
        require(component.get(field) == value, f"{path.name}: component {field} must be {value}")
    attributes = component.get("attributes", {})
    require(isinstance(attributes, dict) and attributes.get("org.gradle.status", "release") == "release", f"{path.name}: component is not a release")
    variants = module.get("variants")
    require(isinstance(variants, list) and bool(variants), f"{path.name}: missing module variants")
    names = set()
    listed_files = set()
    for variant in variants:
        require(isinstance(variant, dict), f"{path.name}: invalid module variant")
        name = variant.get("name")
        require(isinstance(name, str) and bool(name) and name not in names, f"{path.name}: missing or duplicate variant name")
        names.add(name)
        require("available-at" not in variant, f"{path.name}: unexpected redirected variant")
        for key in ("dependencies", "dependencyConstraints"):
            dependencies = variant.get(key, [])
            require(isinstance(dependencies, list), f"{path.name}: invalid {key}")
            for dependency in dependencies:
                require(isinstance(dependency, dict), f"{path.name}: invalid dependency")
                versions = dependency.get("version")
                require(isinstance(versions, dict) and bool(versions.get("requires") or versions.get("strictly")), f"{path.name}: dependency must have a fixed version")
                for field in ("requires", "strictly", "prefers"):
                    if field in versions:
                        require(isinstance(versions[field], str), f"{path.name}: invalid dependency version")
                        fixed_dependency(dependency.get("group", ""), dependency.get("module", ""), versions[field], expected, version, path.name)
        files = variant.get("files", [])
        require(isinstance(files, list), f"{path.name}: invalid variant files")
        for entry in files:
            require(isinstance(entry, dict) and isinstance(entry.get("name"), str), f"{path.name}: invalid file entry")
            target = path.parent / entry["name"]
            require(target in jars and entry.get("url") == entry["name"], f"{path.name}: unexpected artifact reference {entry['name']!r}")
            contents = target.read_bytes()
            require(entry.get("size") == len(contents), f"{path.name}: incorrect size for {target.name}")
            for algorithm in CHECKSUMS:
                if algorithm in entry:
                    require(entry[algorithm] == hashlib.new(algorithm, contents).hexdigest(), f"{path.name}: incorrect {algorithm} for {target.name}")
            listed_files.add(target.name)
    require(f"{artifact}-{version}.jar" in listed_files, f"{path.name}: main JAR is not published in any variant")


def validate_maven_metadata(path: Path, artifact: str, version: str) -> None:
    root = ET.parse(path).getroot()
    require(root.tag == "metadata", f"{path.name}: invalid Maven metadata")
    require(root.findtext("groupId") == GROUP and root.findtext("artifactId") == artifact, f"{path}: incorrect metadata coordinates")
    require([node.text for node in root.findall("versioning/versions/version")] == [version], f"{path}: metadata must contain only version {version}")
    for field in ("latest", "release"):
        value = root.findtext("versioning/" + field)
        require(value is None or value == version, f"{path}: metadata {field} must be {version}")


def validate_signature(path: Path) -> None:
    lines = path.read_text(encoding="ascii").strip().splitlines()
    require(
        len(lines) >= 4 and lines[0] == "-----BEGIN PGP SIGNATURE-----"
        and lines[-1] == "-----END PGP SIGNATURE-----",
        f"{path.name}: expected an ASCII-armored detached PGP signature",
    )
    body = lines[1:-1]
    separator = next((index for index, line in enumerate(body) if not line.strip()), None)
    require(separator is not None, f"{path.name}: missing signature armor separator")
    require(all(":" in line for line in body[:separator]), f"{path.name}: invalid signature armor headers")
    encoded = body[separator + 1:]
    if encoded and encoded[-1].startswith("="):
        require(re.fullmatch(r"=[A-Za-z0-9+/]{4}", encoded.pop()) is not None, f"{path.name}: invalid signature armor checksum")
    try:
        decoded = base64.b64decode("".join(encoded), validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValidationError(f"{path.name}: invalid signature armor payload") from error
    require(bool(decoded), f"{path.name}: empty signature armor payload")


@dataclass(frozen=True)
class ValidationSummary:
    publications: int
    binary_publications: int
    runtime_publications: int
    mapped_jars: int


def verify_repository(
    repository: Path,
    version: str,
    *,
    require_signatures: bool = False,
    runtime_root: Path = PROJECT_ROOT / "runtime",
) -> ValidationSummary:
    version = release_version(version)
    runtimes = discover_runtimes(runtime_root)
    expected = COMPONENT_PUBLICATIONS | AGGREGATES | set(runtimes)
    require(len(expected) == 35, "Expected exactly 35 publications")
    repository = Path(repository).absolute()
    require(repository.is_dir(), f"Missing isolated Maven repository: {repository}")
    group_root = repository / "dev" / "s7a"
    allowed_directories = {repository / "dev", group_root}
    allowed_files: set[Path] = set()
    primary_artifacts: set[Path] = set()

    for artifact in sorted(expected):
        artifact_root = group_root / artifact
        version_root = artifact_root / version
        allowed_directories.update((artifact_root, version_root))
        require(version_root.is_dir(), f"Missing publication directory: {version_root}")
        base = f"{artifact}-{version}"
        pom = version_root / f"{base}.pom"
        require(pom.is_file(), f"Missing POM: {pom}")
        validate_pom(pom, artifact, version, runtimes, expected)
        primary_artifacts.add(pom)
        jars = set()
        if artifact not in AGGREGATES:
            classifiers = ["", "sources", "javadoc"]
            if runtimes.get(artifact):
                classifiers.append("mojang-mapped")
            for classifier in classifiers:
                suffix = "-" + classifier if classifier else ""
                jar = version_root / f"{base}{suffix}.jar"
                require(jar.is_file(), f"Missing {classifier or 'main'} JAR: {jar}")
                validate_jar(jar, classifier, artifact[len(RUNTIME_PREFIX):] if artifact in runtimes else None)
                jars.add(jar)
            primary_artifacts.update(jars)
        module = version_root / f"{base}.module"
        if artifact in COMPONENT_PUBLICATIONS and module.exists():
            validate_module(module, artifact, version, expected, jars)
            primary_artifacts.add(module)
        metadata = artifact_root / "maven-metadata.xml"
        if metadata.exists():
            validate_maven_metadata(metadata, artifact, version)
            allowed_files.add(metadata)

    for artifact in sorted(primary_artifacts):
        signature = artifact.with_name(artifact.name + ".asc")
        require(not require_signatures or signature.is_file(), f"Missing detached signature: {signature}")
        if signature.exists():
            validate_signature(signature)
            allowed_files.add(signature)
    allowed_files.update(primary_artifacts)
    for artifact in sorted(allowed_files):
        for algorithm in CHECKSUMS:
            checksum = artifact.with_name(artifact.name + "." + algorithm)
            if checksum.exists():
                digest = checksum.read_text(encoding="ascii").strip()
                require(digest.lower() == hashlib.new(algorithm, artifact.read_bytes()).hexdigest(), f"{checksum}: checksum mismatch")
                allowed_files.add(checksum)
    for path in sorted(repository.rglob("*")):
        require(not path.is_symlink(), f"Unexpected symlink in release repository: {path}")
        require(
            (path.is_dir() and path in allowed_directories) or (path.is_file() and path in allowed_files),
            f"Unexpected publication, version, or file: {path}",
        )
    return ValidationSummary(len(expected), len(expected - AGGREGATES), len(runtimes), sum(runtimes.values()))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True, help="Fresh, isolated Maven repository directory")
    parser.add_argument("--version", required=True, help="Numeric release version, for example 1.0.0")
    parser.add_argument("--require-signatures", action="store_true", help="Require ASCII-armored signatures; verify their authenticity separately with GPG")
    args = parser.parse_args(argv)
    try:
        summary = verify_repository(args.repository, args.version, require_signatures=args.require_signatures)
    except (ValidationError, OSError, ET.ParseError, zipfile.BadZipFile, UnicodeError, json.JSONDecodeError) as error:
        print(f"Publication validation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Verified {summary.publications} publications for {GROUP}:{args.version}: "
        f"{summary.binary_publications} binary, 2 POM-only, "
        f"{summary.runtime_publications} runtimes, {summary.mapped_jars} mojang-mapped JARs"
        + (" (detached signatures required; GPG verification is separate)" if args.require_signatures else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
