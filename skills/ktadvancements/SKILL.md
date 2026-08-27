---
name: ktadvancements
description: Use when the user wants to build, explain, migrate, or review Kotlin Bukkit/Spigot/Paper plugin code that uses ktAdvancements. Covers dependency selection, runtime artifact choice, advancement modeling, visibility rules, progress updates, store integration, and server-version compatibility decisions.
license: MIT
---

# ktadvancements

Guidance for writing and reviewing Kotlin plugin code that depends on `dev.s7a:ktAdvancements`.

## When to use this skill

Use this skill when the task involves:

- adding or fixing advancement UI/progress code in a Bukkit, Spigot, or Paper plugin
- choosing the correct `ktAdvancements` artifacts for a target Minecraft version
- modeling `KtAdvancement` enums or stores
- wiring advancement grant, revoke, show, and persistence behavior into plugin code
- diagnosing runtime selection issues across Minecraft versions

## Workflow

1. Read [`references/ktadvancements-reference.md`](references/ktadvancements-reference.md) before making non-trivial changes.
2. Pick dependencies first:
   - use `ktAdvancements-api` for custom runtime work
   - use `ktAdvancements-runtime` for multi-version support
   - use version-specific runtime modules only when the target server range is narrow
3. Determine version compatibility from the repository state:
   - inspect `README.md`, `runtime/`, and build files before making claims about supported versions
   - distinguish mapped runtimes through 1.21.11 from unobfuscated runtimes starting at 26.1
   - if the task targets a new Minecraft line, confirm whether modern Paper mappings change the artifact strategy
   - if the task mainly needs a compatibility matrix, read `references/supported-versions.md`
4. Prefer the standard pattern:
   - define advancements as a Kotlin enum implementing `KtAdvancement`
   - create one `KtAdvancements(...)` instance during plugin startup
   - call `showAll(player)` on join or when the tree should refresh
   - update progress through `grant`, `revoke`, `set`, or `transaction`
5. If storage is involved, load the matching reference section:
   - in-memory usage: `Stores`
   - SQLite/MySQL integration: `Stores`
   - plugin-specific persistence: `Custom store`
   - visibility and progression rules: `Visibility and progress`
   - version/runtime failures: `Runtime selection`
   - current compatibility matrix: `Supported versions`
   - unsupported server versions or local packet overrides: `Custom runtime`

## Output expectations

- Prefer concrete Kotlin examples over abstract explanation.
- Keep version advice explicit and grounded in the repository's current runtime set.
- Do not assume every supported Minecraft version uses the same mappings or classifier strategy.
- When a built-in store does not fit, implement `KtAdvancementStore<T>` instead of wrapping unrelated mutable state ad hoc.
- When a built-in runtime does not fit, either add a version-specific runtime module or inject a custom `KtAdvancementRuntime`.
- When custom visibility is required, implement `KtAdvancement.Visibility` rather than adding ad hoc checks around packet sending.
- When persistence is required, use a store implementation instead of plugin-global mutable maps.

## Scope note

This skill is about using `ktAdvancements` in plugin code. If the task is about changing the library internals, inspect the library source directly instead of relying only on the reference.
