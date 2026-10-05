package dev.s7a.ktAdvancements.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.mockito.Mockito

/**
 * Verifies runtime selection without Minecraft server internals.
 */
class RuntimeResolutionTest :
    FunSpec({
        listOf("26.3", "26.3-R0.1-SNAPSHOT", "26.3.build.147-beta", "1.21.11-R0.1-SNAPSHOT").forEach { version ->
            test("resolves the installed runtime for $version") {
                Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                    bukkit.`when`<String> { Bukkit.getBukkitVersion() }.thenReturn(version)
                    val expected = if (version.startsWith("26.3")) "v26_3" else "v1_21_11"
                    KtAdvancementRuntime.resolve().javaClass.name shouldBe
                        "dev.s7a.ktAdvancements.runtime.$expected.KtAdvancementRuntimeImpl"
                }
            }
        }

        test("missing runtimes preserve the normalized version and original cause") {
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<String> { Bukkit.getBukkitVersion() }.thenReturn("99.0.build.12-beta")
                val failure = shouldThrow<RuntimeException> { KtAdvancementRuntime.resolve() }
                failure.message shouldBe "Not found runtime: 99.0"
                (failure.cause is ClassNotFoundException) shouldBe true
            }
        }
    })
