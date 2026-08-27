# ktAdvancements Reference

Use this file when writing or reviewing Kotlin code that depends on `dev.s7a:ktAdvancements`.

## Overview

- ktAdvancements is a packet-based advancement library for Bukkit-compatible servers.
- The API module defines advancement data, stores, and runtime abstractions.
- Runtime modules provide version-specific packet implementations.
- Store modules provide optional persistence helpers for SQLite and MySQL.

For a repository-specific compatibility matrix, read `supported-versions.md`.

## Installation

Use versions that match the current project docs.

```kotlin
repositories {
    maven(url = "https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("dev.s7a:ktAdvancements-api:1.0.0-SNAPSHOT")
    implementation("dev.s7a:ktAdvancements-runtime:1.0.0-SNAPSHOT")
}
```

Optional stores:

```kotlin
implementation("dev.s7a:ktAdvancements-store-sqlite:1.0.0-SNAPSHOT")
implementation("dev.s7a:ktAdvancements-store-mysql:1.0.0-SNAPSHOT")
```

## Runtime selection

### Multi-version runtime

Use the aggregate runtime unless the user explicitly needs a version-specific artifact.

- `ktAdvancements-runtime`: the general aggregate runtime
- `ktAdvancements-runtime-mojang`: the Mojang-mapped aggregate runtime

Through Minecraft 1.21.11, these aggregates select Spigot-mapped and Mojang-mapped artifacts respectively. Starting at Minecraft 26.1, both aggregates select the same normal unobfuscated artifact.

Before recommending one, inspect the repository's current runtime matrix and README wording. Newer Minecraft lines may not preserve the same Spigot/Paper compatibility model as older ones.

### Version-specific runtime

Use version-specific artifacts when the plugin only targets one server line or when the user wants to minimize bundled classes.

Examples:

```kotlin
implementation("dev.s7a:ktAdvancements-runtime-vX_Y_Z:1.0.0-SNAPSHOT")
// Use this classifier only through Minecraft 1.21.11.
implementation("dev.s7a:ktAdvancements-runtime-vX_Y_Z:1.0.0-SNAPSHOT:mojang-mapped")
```

### Version support boundary

- Do not hardcode compatibility assumptions in generated code or advice.
- Check the runtime modules present in `runtime/` and the compatibility notes in `README.md`.
- Through Minecraft 1.21.11, select the mapped artifact appropriate for the target server.
- Starting at Minecraft 26.1, select the normal unobfuscated artifact without a classifier.

When in doubt, describe the mappings and classifier explicitly rather than extrapolating from older versions.

### Custom runtime

Implement `KtAdvancementRuntime` when:

- the target Minecraft version does not have a bundled runtime yet
- the plugin wants to bypass auto-selected runtime lookup
- the environment is unusual enough that packet logic must be supplied manually

Interface shape:

```kotlin
interface KtAdvancementRuntime {
    fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    )
}
```

Minimal wiring example:

```kotlin
class CustomRuntime : KtAdvancementRuntime {
    override fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    ) {
        TODO("Send the correct advancement update packets for the target server version")
    }
}

val ktAdvancements = KtAdvancements(
    advancements = Advancement.entries,
    store = KtAdvancementStore.InMemory(),
    runtime = CustomRuntime(),
)
```

When to prefer a custom runtime over a new library runtime module:

- use a custom runtime in plugin code when the user wants a quick, local workaround
- add a new runtime module in the library when support should be reusable across many plugins

Runtime responsibilities:

- translate `KtAdvancement` models into the server version's advancement packet structures
- respect `reset`, `advancements`, and `removed` exactly as received
- treat `advancements` as the authoritative set to send for this update call
- remove only the IDs present in `removed`

Implementation guidance:

- start from the closest existing runtime module for the target server line
- compare NMS constructor signatures and mapped types before copying code
- expect item, text, or resource wrapper types to change between Minecraft lines
- keep version-specific packet code inside the runtime implementation rather than leaking NMS types into plugin code

Validation guidance:

- verify the runtime loads on server startup
- verify the advancement tab renders
- verify grant, revoke, and partial progress behavior
- verify hidden advancements disappear when moved into `removed`

## Quick start

```kotlin
enum class Advancement(
    override val parent: Advancement?,
    override val requirement: Int = 1,
    override val visibility: KtAdvancement.Visibility = KtAdvancement.Visibility.Always,
    override val defaultGranted: Boolean = false,
) : KtAdvancement<Advancement> {
    Root(null),
    Child(Root),
    ;

    override val id: NamespacedKey
        get() = NamespacedKey("example", name.lowercase())

    override val display = KtAdvancement.Display(
        x = 0f,
        y = 0f,
        icon = ItemStack(Material.STONE),
        title = name,
        description = "Example advancement",
    )
}
```

```kotlin
val ktAdvancements = KtAdvancements(
    Advancement.entries,
    KtAdvancementStore.InMemory(),
)
```

## Core operations

- `showAll(player)`: send the visible advancement tree to a player
- `grant(player, advancement)`: complete the advancement
- `grant(player, advancement, step = n)`: grant partial progress
- `revoke(player, advancement)`: remove progress
- `set(player, advancement, progress)`: set exact progress
- `transaction(player) { ... }`: batch updates and emit one packet set

Prefer `transaction` when multiple advancements are updated together.

## Visibility and progress

Built-in visibility options:

- `Always`
- `HaveProgress`
- `Granted`
- `ParentGranted`
- `Any(...)`
- `All(...)`

Progress is step-based. `requirement` is the number of steps needed to complete an advancement. Keep step counts modest because the library expands them into criteria entries.

## Stores

### In-memory

Use `KtAdvancementStore.InMemory()` for ephemeral progress or tests.

### SQLite / MySQL

Use the store modules when the user wants persistence without building a custom store.

### Custom store

Implement `KtAdvancementStore<T>` when the plugin already has its own persistence layer.

Use a custom store when:

- progress must live in an existing plugin database or repository layer
- advancement state must be joined with other player data
- write batching, caching, or transaction control must follow application-specific rules

Interface shape:

```kotlin
interface KtAdvancementStore<T : KtAdvancement<T>> {
    fun getProgress(
        player: Player,
        advancements: List<T>,
    ): Map<T, Int>

    fun updateProgress(
        player: Player,
        progress: Map<T, Int>,
    )
}
```

Minimal example:

```kotlin
class CustomStore<T : KtAdvancement<T>> : KtAdvancementStore<T> {
    private val values = mutableMapOf<UUID, MutableMap<T, Int>>()

    override fun getProgress(
        player: Player,
        advancements: List<T>,
    ): Map<T, Int> =
        values[player.uniqueId]
            .orEmpty()
            .filterKeys(advancements::contains)

    override fun updateProgress(
        player: Player,
        progress: Map<T, Int>,
    ) {
        values.getOrPut(player.uniqueId, ::mutableMapOf).putAll(progress)
    }
}
```

Important behavior:

- `getProgress` should return only the requested advancements
- missing entries are treated as `0`
- `updateProgress` should update only the supplied advancements and leave all others unchanged
- the store works in terms of progress integers, not granted/revoked booleans

Design guidance:

- use enum keys or stable IDs consistently; do not mix multiple logical advancement sets in one namespace without care
- if the backing store is slow, keep reads and writes scoped to the requested advancements
- when persistence is asynchronous elsewhere in the plugin, still preserve a synchronous view for `KtAdvancements` callers or explicitly stage values before calling library APIs
- if a store is shared across many advancement trees, separate rows by plugin namespace or advancement ID

Selection guidance:

- choose `InMemory` for tests, demos, and disposable state
- choose SQLite when the plugin wants simple local persistence
- choose MySQL when the plugin already depends on a central database
- choose a custom store when the plugin already has its own storage abstraction or schema

## Runtime selection in code

When no runtime is provided, `KtAdvancements(...)` auto-selects a runtime from the server version string.

If automatic selection is not appropriate, pass a custom `KtAdvancementRuntime`.

## Common pitfalls

- Missing runtime artifact: API alone is not enough for packet sending.
- Wrong runtime artifact: mappings differ through 1.21.11, while 26.1 and later use the normal unobfuscated artifact.
- Stale plugin examples: prefer local project modules when working inside the library repo.
- Overusing custom state maps: use stores and transactions instead.
