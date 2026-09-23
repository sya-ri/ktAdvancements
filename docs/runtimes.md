# Runtime options

This library provides multiple runtime options to suit different needs. For more information about Mojang-mapped vs Spigot-mapped runtimes, see [Mojang-mapped vs Spigot-mapped](#mojang-mapped-vs-spigot-mapped).

## 1. Multi-Version Runtime (Recommended)
Use this if you need to support multiple Minecraft versions:
```kotlin
// Choose ONE: Spigot-mapped through 1.21.11; unobfuscated from 26.1 onward
implementation("dev.s7a:ktAdvancements-runtime:1.0.0")

// OR Mojang-mapped through 1.21.11; the same unobfuscated artifacts from 26.1 onward
implementation("dev.s7a:ktAdvancements-runtime-mojang:1.0.0")
```

For Paper 1.20.5+, use the Mojang-mapped aggregate and declare the namespace in your final plugin JAR.
Older Paper remappers cannot process the Java 25 classes included by the Spigot-mapped aggregate;
see [Mojang-mapped vs Spigot-mapped](#mojang-mapped-vs-spigot-mapped) below.

## 2. Version-Specific Runtime
Use this if you only need to support a specific Minecraft version:
```kotlin
// For Spigot/Paper plugins up to 1.21.11
implementation("dev.s7a:ktAdvancements-runtime-v1_17_1:1.0.0")

// For Paper plugins
implementation("dev.s7a:ktAdvancements-runtime-v1_17_1:1.0.0:mojang-mapped")
```

For Minecraft `26.1+`, Spigot and Paper use the same normal unobfuscated runtime artifact:
```kotlin
implementation("dev.s7a:ktAdvancements-runtime-v26_1_2:1.0.0")
```

The 26.1, 26.1.1, and 26.1.2 runtime modules compile against Paper's 26.1.2 dev bundle because Paper does not publish an exact 26.1 bundle. [Spigot documents the 26.1.2 server as fully compatible with the earlier 26.1 releases](https://www.spigotmc.org/threads/spigot-bungeecord-26-1-26-1-1-26-1-2.718646/).

### Supported versions

The following Spigot/Paper releases have runtime modules. Only the listed versions are supported.

| Minecraft series | Supported releases | Server Java |
| --- | --- | --- |
| 1.17 | 1.17.1 | 17 |
| 1.18 | 1.18, 1.18.1, 1.18.2 | 17 |
| 1.19 | 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4 | 17 |
| 1.20 | 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4 | 17 |
| 1.20 | 1.20.6 | 21 |
| 1.21 | 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11 | 21 |
| 26.1 | 26.1, 26.1.1, 26.1.2 | 25 |
| 26.2 | 26.2 | 25 |

## 3. Custom Runtime
If your target version is not supported, you can create your own runtime:

1. Add `ktAdvancements-api` as a dependency:
```kotlin
dependencies {
    implementation("dev.s7a:ktAdvancements-api:1.0.0")
}
```

2. Implement a class based on `KtAdvancementRuntime`:
```kotlin
class YourCustomRuntime : KtAdvancementRuntime {
    override fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    ) {
        TODO("Implement packet sending logic")
    }
}
```

3. Create an instance of your custom runtime and use it:
```kotlin
val customRuntime = YourCustomRuntime()
val ktAdvancements = KtAdvancements(advancements, store, customRuntime)
```

## Mojang-mapped vs Spigot-mapped

From 1.20.5 through 1.21.11, Paper ships with a Mojang-mapped runtime instead of re-obfuscating the server to Spigot mappings. Additionally, CraftBukkit classes are no longer relocated into a versioned package. Plugins that use server internals therefore need the artifact matching the server's mappings namespace.

When shading the multi-version library for Paper 1.20.5+, choose `ktAdvancements-runtime-mojang`
and mark the final plugin JAR as Mojang-mapped. For example, with Shadow:

```kotlin
tasks.shadowJar {
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
}
```

This is necessary for older Paper releases when bundling the 26.x runtimes: Paper 1.20.6's ASM 9.7
remapper rejects Java 25 class files even when that runtime would not be selected. Its remapper also
rejects Java 24 multi-release classes supplied by the game-test plugin's Byte Buddy dependency.
The complete Spigot-mapped aggregate therefore cannot simply be remapped on these older Paper versions.
Use the complete Mojang-mapped aggregate with the manifest above; no runtime or multi-release
implementation classes need to be removed. This follows Paper's documented
[Mojang-mapped plugin loading](https://docs.papermc.io/paper/dev/userdev/#default-mappings-assumption).

Most of this process is done automatically by paperweight, but there are some important things to know when using server internals (or "NMS") from now on:

- **Minecraft 1.20.5 through 1.21.11**:
  - By default, all Spigot/Bukkit plugins will be assumed to be Spigot-mapped if they do not specify their mappings namespace in the manifest
  - All Paper plugins will be assumed to be Mojang-mapped if they do not specify their mappings namespace in the manifest
  - Spigot-mapped plugins will need to be deobfuscated on first load, Mojang-mapped plugins will not
- **Minecraft 26.1 and later**:
  - Minecraft server distributions are unobfuscated, so there is no separate Spigot-mapped runtime artifact and no re-obfuscation step
  - `reobfJar` is not used; each version module publishes its unobfuscated normal JAR
  - `ktAdvancements-runtime` and `ktAdvancements-runtime-mojang` both depend on that same normal JAR for these versions, without the `mojang-mapped` classifier

For more details, please refer to the [Paper userdev documentation](https://docs.papermc.io/paper/dev/userdev/#1205-and-beyond) and the [Paper 26.1 announcement](https://papermc.io/news/26-1/).
