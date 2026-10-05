# Changelog

## v1.0.3

### Added

- `KtAdvancementRuntime.resolve()` selects the official runtime for the running server and returns it for dependency injection or composition. It is callable from Kotlin and Java and uses the same selection as the default `KtAdvancements` constructor.
- Keep the appropriate runtime artifact on the classpath. Resolution does not download dependencies or fall back to a different version, and preserves the existing missing-runtime error contract.

## v1.0.2

### Fixed

- Enable advancement completion notifications on Minecraft 1.21.5 and later when `showToast` is enabled. Partial progress, revoked progress and tree resets do not request notifications.
- Keep initial and reconnect tree synchronization silent, including advancements that are already complete.

## v1.0.1

### Added

- Minecraft 26.3 support, available through both runtime aggregates and the version-specific runtime artifact. Requires Java 25.

## v1.0.0

### Added

- Initial stable release of the packet-based Kotlin advancement API for Spigot and Paper plugins.
- Advancement trees, step-based progress, grant/revoke operations, visibility rules, and batched updates.
- In-memory storage and optional SQLite/MySQL store modules, with interfaces for custom stores and runtimes.
- Runtime implementations for 30 Minecraft releases from 1.17.1 through 26.2, including 26.1, 26.1.1, and 26.1.2.
- Spigot-mapped and Mojang-mapped runtime aggregates. Through 1.21.11, version modules retain the
  `mojang-mapped` classifier; from 26.1 onward, both aggregates use the same unobfuscated JARs.
- Real-server packet tests and committed vanilla-client screenshots for every supported version,
  checking advancement progress at 0/10, 3/10, 10/10, and 9/10 after revoking a step.
- Signed Maven Central artifacts with sources and Javadoc, and a CI-gated release workflow.
- A distributable agent skill with dependency, runtime, advancement, and storage examples.

### Compatibility

- Server Java requirements: Java 17 through Minecraft 1.20.4, Java 21 for 1.20.5–1.21.11,
  and Java 25 from 26.1 onward. Building this repository requires JDK 25.
- Paper 1.20.5+ plugins bundling the complete runtime set must use `ktAdvancements-runtime-mojang`
  and declare `paperweight-mappings-namespace: mojang` in their final plugin JAR.
- Minecraft snapshots are not supported. See the README for the exact supported release list.
