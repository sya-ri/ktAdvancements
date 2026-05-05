# Supported Versions

This file is a snapshot of the versions supported by the current repository state.

Use it when an agent needs a concise compatibility matrix without scanning `README.md` and `runtime/` manually. If the repository changes, update this file together with the runtime modules and README.

## Aggregate summary

- Multi-version runtime exists via `ktAdvancements-runtime`
- Mojang-mapped aggregate runtime exists via `ktAdvancements-runtime-mojang`
- Not every runtime line necessarily supports both Spigot and Paper

## Current runtime modules

Spigot/Paper-compatible lines currently present:

- `1.17.1`
- `1.18`
- `1.18.1`
- `1.18.2`
- `1.19`
- `1.19.1`
- `1.19.2`
- `1.19.3`
- `1.19.4`
- `1.20`
- `1.20.1`
- `1.20.2`
- `1.20.3`
- `1.20.4`
- `1.20.6`
- `1.21`
- `1.21.1`
- `1.21.3`
- `1.21.4`
- `1.21.5`
- `1.21.6`
- `1.21.7`
- `1.21.8`
- `1.21.9`
- `1.21.10`
- `1.21.11`

Paper-only lines currently present:

- `26.1.2`

## Source of truth

When this file and the code disagree, prefer the code and then update this file.

Primary places to verify:

- `runtime/`
- `README.md`
- `runtime/build.gradle.kts`
