# Releasing ktAdvancements

Releases are prepared in a pull request to `master`. Merging the PR does not immediately upload
artifacts: the **Minecraft game tests** workflow must first pass on that exact master commit.
The **Release** workflow then publishes to Maven Central and creates a stable GitHub Release.
PR workflows never receive publishing secrets or upload artifacts to Central.

## One-time repository setup

Before merging the first release PR, add these **Actions repository secrets** in
**Settings → Secrets and variables → Actions**. Their names match ktConfig's publication workflow.
Do not commit credentials or paste private keys into issues, PRs, or logs.

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Username from a Central Portal **user token**, not your login name |
| `MAVEN_CENTRAL_PASSWORD` | Password from the same Central Portal user token |
| `SIGNING_KEY` | ASCII-armored OpenPGP private signing key; actual newlines or literal `\n` are accepted |
| `SIGNING_PASSWORD` | Passphrase for that signing key |

The Central account must be authorized to publish the `dev.s7a` namespace, and Central must be
able to find the corresponding public signing key. See the
[Central publishing requirements](https://central.sonatype.org/publish/requirements/) and
[publishing plugin's credential instructions](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

The workflow uses the built-in `GITHUB_TOKEN` with `actions: read` and `contents: write` for
CI checks, version tags, and GitHub Releases. No personal access token or GitHub environment is
required. Keep `master` and release workflow changes protected by review. This PR does not
change repository protection, environments, secrets, or immutable-release settings.

Missing secrets stop the workflow **before** a version tag is reserved or anything is uploaded.

## Prepare a release PR

1. Set the stable version in the root `build.gradle.kts` (for example, `1.0.0`). All 35 publications
   inherit it; do not change Paper/Spigot's upstream `SNAPSHOT` dependency versions.
2. Update every ktAdvancements dependency example in `README.md` and
   `skills/ktadvancements/references/ktadvancements-reference.md`. Stable examples use `mavenCentral()`.
3. Put `## v<version>` first in `CHANGELOG.md`, with the user-facing changes below it. Older entries
   stay intact. The matching section becomes the GitHub Release notes; no release date is guessed in advance.
4. Run the checks below, open the PR, and review all game-test and screenshot results before merging.

```sh
python3 -B scripts/release/release.py check
python3 -B -m unittest discover -s scripts/release/tests -v
./gradlew -p buildSrc test --no-daemon
./gradlew build --no-daemon
```

Build with JDK 25; Java 17 and 21 toolchains are also used. Ordinary `build` does not launch Minecraft
or publish anything. See the README for the real-server and screenshot test commands.

For a complete publication rehearsal, use a **disposable test signing key**, as demonstrated in
the `Stage signed release publications` step of `.github/workflows/game-test.yml`:

```sh
# Set signingInMemoryKey and signingInMemoryKeyPassword via ORG_GRADLE_PROJECT_ environment variables.
# Start with a fresh build/release-repository so previous versions cannot mask missing output.
./gradlew publishAllPublicationsToReleaseValidationRepository --no-daemon
python3 -B scripts/release/verify_publications.py \
  --repository build/release-repository --version 1.0.0 --require-signatures
python3 -B scripts/release/release.py verify-signatures
```

`ReleaseValidation` is a file repository under `build/`, not `mavenLocal()` or a remote server.
The validator checks POM metadata and dependencies, all runtime classifiers, real sources/Javadoc
archives, optional Gradle metadata/checksums, and detached signatures. The final command verifies
those signatures cryptographically using the isolated GPG keyring. Never use a production key for PR CI.

## After merge

1. CI builds the current source and stages all publications with an ephemeral test key. Every runtime
   receives real-server packet tests and four screenshot comparisons against committed baselines.
2. Release preflight accepts only a successful `push` run of `.github/workflows/game-test.yml`
   from this repository's `master`. It verifies every expected job and the publication-validation
   steps. A new release must still be the current master HEAD; a newer commit must pass its own CI.
3. A separate fresh job checks out the exact tested SHA, builds and validates all signed publications
   again, and confirms that Central has none of this version's POMs. It then reserves `v<version>`
   at that SHA. **The workflow never moves, replaces, or deletes a version tag.**
4. Vanniktech's base publishing plugin uploads the 35 publications in **one deployment** and waits
   for `PUBLISHED`, not just `VALIDATED`. The POM SCM tag records the exact release commit.
5. The workflow waits up to 30 minutes for every expected POM, JAR, Gradle metadata file, and detached
   signature to be publicly downloadable. It revalidates their structure, commit identity, and signatures.
6. Only then does it create the non-draft, non-prerelease GitHub Release from `CHANGELOG.md`.

The 35 publications are the API, SQLite/MySQL stores, 30 version runtimes, and two POM-only aggregates.
The 33 binary publications include sources and Dokka Javadoc. Legacy runtimes retain both the
reobfuscated main JAR and `mojang-mapped` JAR; 26.x runtimes keep their normal unobfuscated JAR.
The example, game-test plugin, server/client software, and test screenshots are not Maven publications.

Publishing is serialized and never automatically canceled by a newer push. Subsequent master commits
with an already released version are no-ops; bump the version in a new release PR when ready.

## Recover a stopped release

Inspect the failed run, the version tag, and the [Central Portal](https://central.sonatype.com/publishing).
Do not delete/move the tag or try to overwrite a published version. A public 404 does **not** prove
there is no pending deployment inside Portal, so uncertain uploads are never automatically retried.

Use **Actions → Release → Run workflow**, select **master**, enter the exact version, and choose:

| Mode | When to use it |
| --- | --- |
| `publish` | No tag or Central deployment exists; for example, the initial run stopped for missing secrets |
| `finish-release` | The tag is reserved and Central has published the version, but public-file verification or GitHub Release creation stopped; does not upload anything |
| `retry-publish` | The tag is reserved, all public POMs are absent, and you have confirmed in Portal that no published or pending deployment exists |

Selecting `retry-publish` is an explicit acknowledgement of that Portal check. If a deployment is
still processing, wait; if it is published, use `finish-release`. Partial visibility, network errors,
changed tags, unexpected drafts, and failed/missing CI are blocking conditions, not permission to upload.

Recovery uses the **original tag commit**, which must remain in master history and have successful
game-test/publication CI. It does not require master HEAD to stay unchanged after an upload starts.
Finalization rechecks the tag and never overwrites an existing Release. If the Release already exists
with the expected commit binding, preflight exits successfully without changes. Recovering an older
version does not force it to become GitHub's latest release.

## Reference projects

The publication conventions follow [ktConfig](https://github.com/sya-ri/ktConfig/tree/releases/v2)
(Central Portal, signing, Dokka, metadata checks) and
[ktInventory](https://github.com/sya-ri/ktInventory/tree/releases/v2)
(stable Maven Central dependency examples and changelog sections).
[strata](https://github.com/sya-ri/strata) informs the exact-commit/CI gates and recovery safeguards.
Their manual workflows are adapted here to the requested post-merge automation.
