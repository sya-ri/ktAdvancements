# Runtime options

This library provides multiple runtime options to suit different needs. For more information about Mojang-mapped vs Spigot-mapped runtimes, see [Mojang-mapped vs Spigot-mapped](#mojang-mapped-vs-spigot-mapped).

## 1. Multi-Version Runtime (Recommended)
Use this if you need to support multiple Minecraft versions:
```kotlin
// Choose ONE: Spigot-mapped through 1.21.11; unobfuscated from 26.1 onward
implementation("dev.s7a:ktAdvancements-runtime:1.0.1")

// OR Mojang-mapped through 1.21.11; the same unobfuscated artifacts from 26.1 onward
implementation("dev.s7a:ktAdvancements-runtime-mojang:1.0.1")
```

For Paper 1.20.5+, use the Mojang-mapped aggregate and declare the namespace in your final plugin JAR.
Older Paper remappers cannot process the Java 25 classes included by the Spigot-mapped aggregate;
see [Mojang-mapped vs Spigot-mapped](#mojang-mapped-vs-spigot-mapped) below.

## 2. Version-Specific Runtime
Use this if you only need to support a specific Minecraft version:
```kotlin
// For Spigot/Paper plugins up to 1.21.11
implementation("dev.s7a:ktAdvancements-runtime-v1_17_1:1.0.1")

// For Paper plugins
implementation("dev.s7a:ktAdvancements-runtime-v1_17_1:1.0.1:mojang-mapped")
```

For Minecraft `26.1+`, Spigot and Paper use the same normal unobfuscated runtime artifact:
```kotlin
implementation("dev.s7a:ktAdvancements-runtime-v26_3:1.0.1")
```

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
| 26.3 | 26.3 | 25 |

## 3. Custom Runtime
If your target version is not supported, you can create your own runtime:

1. Add `ktAdvancements-api` as a dependency:
```kotlin
dependencies {
    implementation("dev.s7a:ktAdvancements-api:1.0.1")
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

The aggregate contains runtimes requiring newer Java versions, so older Paper remappers cannot safely remap the complete Spigot-mapped bundle.
The Mojang-mapped aggregate and manifest above avoid that remapping step.

Through 1.21.11, choose the artifact matching the server's mapping namespace.
From 26.1 onward, both aggregate runtimes use the same unobfuscated version artifacts without a classifier.
See Paper's [Mojang-mapped plugin guidance](https://docs.papermc.io/paper/dev/userdev/#default-mappings-assumption) for the upstream loading rules.
