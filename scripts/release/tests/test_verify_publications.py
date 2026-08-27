"""Synthetic release repositories: no Gradle, network, credentials, or uploads."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_publications as publications


VERSION = "1.0.0"
RUNTIMES = publications.discover_runtimes(publications.PROJECT_ROOT / "runtime")
ARTIFACTS = publications.COMPONENT_PUBLICATIONS | publications.AGGREGATES | set(RUNTIMES)
LEGACY = "ktAdvancements-runtime-v1_21_11"
MODERN = "ktAdvancements-runtime-v26_1"
NS = publications.NS
SIGNATURE = "-----BEGIN PGP SIGNATURE-----\nVersion: fixture\n\nc2lnbmF0dXJl\n-----END PGP SIGNATURE-----\n"


def child(parent: ET.Element, name: str, value: str | None = None) -> ET.Element:
    node = ET.SubElement(parent, f"{{{publications.POM_NAMESPACE}}}{name}")
    node.text = value
    return node


def write_jar(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


class PublicationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="ktadvancements-publications-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.runtime_root = self.root / "runtime"
        self.runtime_root.mkdir()
        for artifact in RUNTIMES:
            (self.runtime_root / artifact[len(publications.RUNTIME_PREFIX):]).mkdir()
        for artifact in ARTIFACTS:
            self.directory(artifact).mkdir(parents=True)
            self.make_pom(artifact)
            if artifact not in publications.AGGREGATES:
                entries = {"dev/s7a/ktAdvancements/Example.class": b"\xca\xfe\xba\xbe\x00\x00\x00\x3d"}
                if artifact in RUNTIMES:
                    runtime = artifact[len(publications.RUNTIME_PREFIX):]
                    entries = {f"dev/s7a/ktAdvancements/runtime/{runtime}/KtAdvancementRuntimeImpl.class": b"\xca\xfe\xba\xbe\x00\x00\x00\x3d"}
                write_jar(self.file(artifact, ".jar"), entries)
                write_jar(self.file(artifact, "-sources.jar"), {"dev/s7a/Example.kt": b"package dev.s7a\nclass Example\n"})
                write_jar(self.file(artifact, "-javadoc.jar"), {"dev/s7a/Example.html": b"<!DOCTYPE html><html><head><title>Example</title></head><body>Example API documentation</body></html>"})
                if RUNTIMES.get(artifact):
                    write_jar(self.file(artifact, "-mojang-mapped.jar"), entries)

    def directory(self, artifact: str) -> Path:
        return self.repository / "dev" / "s7a" / artifact / VERSION

    def file(self, artifact: str, suffix: str) -> Path:
        return self.directory(artifact) / f"{artifact}-{VERSION}{suffix}"

    def make_pom(self, artifact: str) -> None:
        root = ET.Element(f"{{{publications.POM_NAMESPACE}}}project")
        for name, value in (
            ("modelVersion", "4.0.0"), ("groupId", publications.GROUP), ("artifactId", artifact),
            ("version", VERSION), ("name", artifact), ("description", "Minecraft advancement library"),
            ("url", "https://github.com/sya-ri/ktAdvancements"),
        ):
            child(root, name, value)
        if artifact in publications.AGGREGATES:
            child(root, "packaging", "pom")
        license_node = child(child(root, "licenses"), "license")
        child(license_node, "name", "MIT License")
        child(license_node, "url", "https://github.com/sya-ri/ktAdvancements/blob/master/LICENSE")
        developer = child(child(root, "developers"), "developer")
        for name, value in (("id", "sya-ri"), ("name", "sya-ri"), ("email", "contact@s7a.dev")):
            child(developer, name, value)
        scm = child(root, "scm")
        for name, value in (
            ("url", "https://github.com/sya-ri/ktAdvancements"),
            ("connection", "scm:git:https://github.com/sya-ri/ktAdvancements.git"),
            ("developerConnection", "scm:git:ssh://git@github.com/sya-ri/ktAdvancements.git"),
        ):
            child(scm, name, value)
        if artifact in publications.COMPONENT_PUBLICATIONS:
            dependencies = child(root, "dependencies")
            self.add_dependency(dependencies, "org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21")
            if artifact in publications.STORES:
                self.add_dependency(dependencies, publications.GROUP, publications.API, VERSION)
        if artifact in publications.AGGREGATES:
            dependencies = child(root, "dependencies")
            for runtime, legacy in RUNTIMES.items():
                dependency = self.add_dependency(dependencies, publications.GROUP, runtime, VERSION)
                if artifact.endswith("-mojang") and legacy:
                    child(dependency, "classifier", "mojang-mapped")
        ET.ElementTree(root).write(self.file(artifact, ".pom"), encoding="utf-8", xml_declaration=True)

    @staticmethod
    def add_dependency(parent: ET.Element, group: str, artifact: str, version: str) -> ET.Element:
        dependency = child(parent, "dependency")
        for name, value in (("groupId", group), ("artifactId", artifact), ("version", version), ("scope", "compile")):
            child(dependency, name, value)
        return dependency

    @contextlib.contextmanager
    def edit_pom(self, artifact: str):
        path = self.file(artifact, ".pom")
        document = ET.parse(path)
        yield document.getroot()
        document.write(path, encoding="utf-8", xml_declaration=True)

    def verify(self, *, require_signatures: bool = False) -> publications.ValidationSummary:
        return publications.verify_repository(
            self.repository, VERSION, runtime_root=self.runtime_root, require_signatures=require_signatures,
        )

    def assert_invalid(self, message: str) -> None:
        with self.assertRaisesRegex(publications.ValidationError, message):
            self.verify()

    def make_module(self, artifact: str = publications.API) -> dict:
        jar = self.file(artifact, ".jar")
        metadata = {
            "formatVersion": "1.1",
            "component": {"group": publications.GROUP, "module": artifact, "version": VERSION, "attributes": {"org.gradle.status": "release"}},
            "variants": [{
                "name": "apiElements",
                "dependencies": [{"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "2.3.21"}}],
                "files": [{"name": jar.name, "url": jar.name, "size": jar.stat().st_size, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}],
            }],
        }
        self.file(artifact, ".module").write_text(json.dumps(metadata), encoding="utf-8")
        return metadata

    def test_all_35_publications_and_legacy_classifiers_pass(self) -> None:
        result = self.verify()
        self.assertEqual(result, publications.ValidationSummary(35, 33, 30, 26))

    def test_real_runtime_directories_match_current_release(self) -> None:
        self.assertEqual(len(RUNTIMES), 30)
        self.assertTrue(RUNTIMES[LEGACY])
        self.assertFalse(RUNTIMES[MODERN])

    def test_release_version_is_numeric_semver_only(self) -> None:
        for version in ("1.0.0-SNAPSHOT", "1.0.0-rc.1", "1.0.0+build", "v1.0.0", "1.0", "01.0.0", " 1.0.0", "1.0.0\n", "1\u0660.0.0"):
            with self.subTest(version=version), self.assertRaises(publications.ValidationError):
                publications.release_version(version)
        self.assertEqual(publications.release_version("1.0.0"), VERSION)

    def test_missing_legacy_mojang_mapped_jar_fails(self) -> None:
        self.file(LEGACY, "-mojang-mapped.jar").unlink()
        self.assert_invalid("Missing mojang-mapped JAR")

    def test_unexpected_modern_classifier_fails(self) -> None:
        write_jar(self.file(MODERN, "-mojang-mapped.jar"), {"Example.class": b"\xca\xfe\xba\xbe"})
        self.assert_invalid("Unexpected publication")

    def test_wrong_runtime_class_fails(self) -> None:
        write_jar(self.file(MODERN, ".jar"), {"wrong/Runtime.class": b"\xca\xfe\xba\xbe"})
        self.assert_invalid("missing runtime implementation")

    def test_missing_main_jar_fails(self) -> None:
        self.file(publications.API, ".jar").unlink()
        self.assert_invalid("Missing main JAR")

    def test_missing_javadoc_jar_fails(self) -> None:
        self.file(publications.API, "-javadoc.jar").unlink()
        self.assert_invalid("Missing javadoc JAR")

    def test_empty_javadoc_jar_fails(self) -> None:
        write_jar(self.file(publications.API, "-javadoc.jar"), {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n"})
        self.assert_invalid("generated HTML")

    def test_non_html_javadoc_entry_fails(self) -> None:
        write_jar(self.file(publications.API, "-javadoc.jar"), {"index.html": b"Documentation is not available."})
        self.assert_invalid("generated HTML")

    def test_missing_sources_jar_fails(self) -> None:
        self.file(publications.API, "-sources.jar").unlink()
        self.assert_invalid("Missing sources JAR")

    def test_empty_sources_jar_fails(self) -> None:
        write_jar(self.file(publications.API, "-sources.jar"), {"Example.kt": b"  \n"})
        self.assert_invalid("non-empty Kotlin or Java source")

    def test_missing_aggregate_dependency_fails(self) -> None:
        with self.edit_pom("ktAdvancements-runtime") as root:
            dependencies = root.find("m:dependencies", NS)
            dependencies.remove(dependencies[0])
        self.assert_invalid("exactly 30")

    def test_duplicate_aggregate_dependency_fails(self) -> None:
        with self.edit_pom("ktAdvancements-runtime") as root:
            dependencies = root.find("m:dependencies", NS)
            dependencies[1].find("m:artifactId", NS).text = dependencies[0].findtext("m:artifactId", namespaces=NS)
        self.assert_invalid("duplicate aggregate")

    def test_mojang_aggregate_requires_old_classifier(self) -> None:
        with self.edit_pom("ktAdvancements-runtime-mojang") as root:
            for dependency in root.findall("m:dependencies/m:dependency", NS):
                if dependency.findtext("m:artifactId", namespaces=NS) == LEGACY:
                    dependency.remove(dependency.find("m:classifier", NS))
        self.assert_invalid("classifier must be 'mojang-mapped'")

    def test_mojang_aggregate_forbids_new_classifier(self) -> None:
        with self.edit_pom("ktAdvancements-runtime-mojang") as root:
            for dependency in root.findall("m:dependencies/m:dependency", NS):
                if dependency.findtext("m:artifactId", namespaces=NS) == MODERN:
                    child(dependency, "classifier", "mojang-mapped")
        self.assert_invalid("classifier must be ''")

    def test_spigot_aggregate_forbids_classifier(self) -> None:
        with self.edit_pom("ktAdvancements-runtime") as root:
            child(root.find("m:dependencies/m:dependency", NS), "classifier", "mojang-mapped")
        self.assert_invalid("classifier must be ''")

    def test_optional_aggregate_dependency_fails(self) -> None:
        with self.edit_pom("ktAdvancements-runtime") as root:
            child(root.find("m:dependencies/m:dependency", NS), "optional", "true")
        self.assert_invalid("must not be optional")

    def test_missing_required_metadata_fails(self) -> None:
        for field in ("description", "scm/connection", "developers/developer/email"):
            with self.subTest(field=field):
                self.make_pom(publications.API)
                with self.edit_pom(publications.API) as root:
                    root.find("/".join("m:" + part for part in field.split("/")), NS).text = ""
                self.assert_invalid("missing")

    def test_non_mit_license_fails(self) -> None:
        with self.edit_pom(publications.API) as root:
            root.find("m:licenses/m:license/m:name", NS).text = "Apache License 2.0"
        self.assert_invalid("license must be MIT")

    def test_wrong_pom_coordinates_fail(self) -> None:
        for field, wrong in (("groupId", "invalid.group"), ("artifactId", "ktAdvancements-example"), ("version", "1.0.1")):
            with self.subTest(field=field):
                self.make_pom(publications.API)
                with self.edit_pom(publications.API) as root:
                    root.find("m:" + field, NS).text = wrong
                self.assert_invalid(field + " must be")

    def test_snapshot_and_dynamic_dependencies_fail(self) -> None:
        for version in ("2.3.21-SNAPSHOT", "2.+", "[2.0,3.0)", "latest.release", "RELEASE", "$" + "{kotlin.version}", "*"):
            with self.subTest(version=version):
                self.make_pom(publications.API)
                with self.edit_pom(publications.API) as root:
                    root.find("m:dependencies/m:dependency/m:version", NS).text = version
                self.assert_invalid("non-fixed release version")

    def test_project_dependency_version_must_match(self) -> None:
        with self.edit_pom("ktAdvancements-store-mysql") as root:
            for dependency in root.findall("m:dependencies/m:dependency", NS):
                if dependency.findtext("m:artifactId", namespaces=NS) == publications.API:
                    dependency.find("m:version", NS).text = "0.9.0"
        self.assert_invalid("must use version 1.0.0")

    def test_store_requires_api_dependency(self) -> None:
        with self.edit_pom("ktAdvancements-store-mysql") as root:
            dependencies = root.find("m:dependencies", NS)
            dependencies.remove(dependencies[1])
        self.assert_invalid("store must depend on the API")

    def test_runtime_must_not_publish_paper_dependencies(self) -> None:
        with self.edit_pom(MODERN) as root:
            self.add_dependency(child(root, "dependencies"), "io.papermc.paper", "paper-api", "26.1.2.build.60")
        self.assert_invalid("Paper development dependencies")

    def test_runtime_manual_pom_must_stay_dependency_free(self) -> None:
        with self.edit_pom(MODERN) as root:
            self.add_dependency(child(root, "dependencies"), "org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21")
        self.assert_invalid("manual runtime POM")

    def test_parent_cannot_hide_inherited_dependencies(self) -> None:
        with self.edit_pom(MODERN) as root:
            parent = child(root, "parent")
            child(parent, "groupId", "io.papermc")
            child(parent, "artifactId", "inherited-dependencies")
            child(parent, "version", "1.0.0-SNAPSHOT")
        self.assert_invalid("self-contained")

    def test_extra_publications_and_versions_fail(self) -> None:
        paths = (
            self.repository / "dev/s7a/ktAdvancements-example/1.0.0",
            self.repository / "dev/s7a/ktAdvancements-game-test/1.0.0",
            self.repository / "dev/s7a/buildSrc/1.0.0",
            self.repository / "other/group/foreign/1.0.0",
            self.directory(publications.API).parent / "0.9.0",
        )
        for path in paths:
            with self.subTest(path=path):
                path.mkdir(parents=True)
                self.assert_invalid("Unexpected publication")
                path.rmdir()
                # Remove only the empty directories created for this case.
                ancestor = path.parent
                while ancestor != self.repository and not any(ancestor.iterdir()):
                    next_ancestor = ancestor.parent
                    ancestor.rmdir()
                    ancestor = next_ancestor

    def test_extra_versioned_file_fails(self) -> None:
        (self.directory(publications.API) / "ktAdvancements-api-0.9.0.pom").write_text("<project/>", encoding="utf-8")
        self.assert_invalid("Unexpected publication")

    def test_aggregate_jar_fails(self) -> None:
        write_jar(self.file("ktAdvancements-runtime", ".jar"), {"Example.class": b"\xca\xfe\xba\xbe"})
        self.assert_invalid("Unexpected publication")

    def test_wrong_runtime_source_count_fails(self) -> None:
        (self.runtime_root / "v26_3").mkdir()
        self.assert_invalid("exactly 30 runtime")

    def test_valid_optional_metadata_and_checksums_pass(self) -> None:
        self.make_module()
        metadata = self.directory(publications.API).parent / "maven-metadata.xml"
        metadata.write_text(
            "<metadata><groupId>dev.s7a</groupId><artifactId>ktAdvancements-api</artifactId>"
            "<versioning><latest>1.0.0</latest><release>1.0.0</release>"
            "<versions><version>1.0.0</version></versions></versioning></metadata>", encoding="utf-8",
        )
        for path in (metadata, self.file(publications.API, ".pom")):
            path.with_name(path.name + ".sha256").write_text(hashlib.sha256(path.read_bytes()).hexdigest(), encoding="ascii")
        self.verify()

    def test_maven_metadata_cannot_hide_a_second_version(self) -> None:
        metadata = self.directory(publications.API).parent / "maven-metadata.xml"
        metadata.write_text(
            "<metadata><groupId>dev.s7a</groupId><artifactId>ktAdvancements-api</artifactId>"
            "<versioning><versions><version>0.9.0</version><version>1.0.0</version></versions></versioning></metadata>", encoding="utf-8",
        )
        self.assert_invalid("metadata must contain only")

    def test_module_coordinate_mismatch_fails(self) -> None:
        module = self.make_module()
        module["component"]["version"] = "1.0.0-SNAPSHOT"
        self.file(publications.API, ".module").write_text(json.dumps(module), encoding="utf-8")
        self.assert_invalid("component version")

    def test_module_dependency_snapshot_fails(self) -> None:
        module = self.make_module()
        module["variants"][0]["dependencies"][0]["version"]["requires"] = "2.4.0-SNAPSHOT"
        self.file(publications.API, ".module").write_text(json.dumps(module), encoding="utf-8")
        self.assert_invalid("non-fixed release version")

    def test_module_artifact_hash_mismatch_fails(self) -> None:
        module = self.make_module()
        module["variants"][0]["files"][0]["sha256"] = "0" * 64
        self.file(publications.API, ".module").write_text(json.dumps(module), encoding="utf-8")
        self.assert_invalid("incorrect sha256")

    def test_runtime_module_metadata_is_not_allowed(self) -> None:
        self.file(MODERN, ".module").write_text("{}", encoding="utf-8")
        self.assert_invalid("Unexpected publication")

    def test_checksum_mismatch_fails(self) -> None:
        self.file(publications.API, ".pom.sha512").write_text("0" * 128, encoding="ascii")
        self.assert_invalid("checksum mismatch")

    def test_required_signatures_cover_pom_jar_and_module(self) -> None:
        self.make_module()
        signed_files = [path for path in self.repository.rglob("*") if path.suffix in {".pom", ".jar", ".module"}]
        for path in signed_files:
            path.with_name(path.name + ".asc").write_text(SIGNATURE, encoding="ascii")
        self.verify(require_signatures=True)
        for suffix in (".pom", ".jar", "-mojang-mapped.jar", "-sources.jar", "-javadoc.jar", ".module"):
            artifact = LEGACY if suffix == "-mojang-mapped.jar" else publications.API
            signature = self.file(artifact, suffix + ".asc")
            with self.subTest(suffix=suffix):
                signature.unlink()
                with self.assertRaisesRegex(publications.ValidationError, "Missing detached signature"):
                    self.verify(require_signatures=True)
                signature.write_text(SIGNATURE, encoding="ascii")

    def test_invalid_existing_signature_fails_even_without_require_flag(self) -> None:
        self.file(publications.API, ".pom.asc").write_text("not a signature", encoding="ascii")
        self.assert_invalid("ASCII-armored detached")

    def test_wrong_signature_payload_fails(self) -> None:
        self.file(publications.API, ".pom.asc").write_text(SIGNATURE.replace("c2lnbmF0dXJl", "invalid!"), encoding="ascii")
        self.assert_invalid("invalid signature armor payload")

    def test_cli_returns_nonzero_with_actionable_error(self) -> None:
        self.file(publications.API, "-javadoc.jar").unlink()
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            result = publications.main(["--repository", str(self.repository), "--version", VERSION])
        self.assertEqual(result, 1)
        self.assertIn("Missing javadoc JAR", output.getvalue())


if __name__ == "__main__":
    unittest.main()
