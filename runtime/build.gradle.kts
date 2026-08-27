import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

data class MinecraftVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
) : Comparable<MinecraftVersion> {
    override fun compareTo(other: MinecraftVersion): Int =
        compareValuesBy(this, other, MinecraftVersion::major, MinecraftVersion::minor, MinecraftVersion::patch)

    companion object {
        fun parse(value: String): MinecraftVersion {
            val parts = value.split('_').map(String::toInt)
            require(parts.size in 2..3) { "Unsupported Minecraft version: $value" }
            return MinecraftVersion(parts[0], parts[1], parts.getOrElse(2) { 0 })
        }
    }
}

plugins {
    `maven-publish`
    signing
    alias(libs.plugins.paperweight.userdev) apply false
}

subprojects {
    apply(plugin = "io.papermc.paperweight.userdev")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val name = project.name.drop(1)
    val minecraftVersion = MinecraftVersion.parse(name)
    val minecraftVersion1_20_5 = MinecraftVersion(1, 20, 5)
    val minecraftVersion26_1 = MinecraftVersion(26, 1)
    val javaVersionNumber =
        when {
            minecraftVersion >= minecraftVersion26_1 -> 25
            minecraftVersion >= minecraftVersion1_20_5 -> 21
            else -> 17
        }
    val javaVersion = JavaVersion.toVersion(javaVersionNumber)
    val usesUnobfuscatedJar = minecraftVersion >= minecraftVersion26_1
    val paperVersion =
        when {
            name in setOf("26_1", "26_1_1", "26_1_2") -> "26.1.2.build.+"
            usesUnobfuscatedJar -> "${name.replace('_', '.')}.build.+"
            else -> "${name.replace('_', '.')}-R0.1-SNAPSHOT"
        }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersionNumber))
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    kotlin {
        jvmToolchain(javaVersionNumber)
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersionNumber.toString()))
        }
    }

    repositories {
        // Paper
        maven(url = "https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        implementation(project(":api"))

        extensions.getByType<PaperweightUserDependenciesExtension>().paperDevBundle(paperVersion)
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    if (!usesUnobfuscatedJar) {
        tasks.assemble {
            dependsOn(tasks.named("reobfJar"))
        }
    }

    applyPublishingConfig(
        "ktAdvancements-runtime-v$name",
        publication = {
            if (usesUnobfuscatedJar) {
                artifact(tasks.jar)
            } else {
                artifact(
                    layout.buildDirectory.file(
                        "libs/${project.name}-${project.version}-reobf.jar",
                    ),
                ) {
                    // spigot-mapped
                    builtBy(tasks.named("reobfJar"))
                }
                artifact(tasks.jar) {
                    classifier = "mojang-mapped"
                }
            }
            artifact(sourceJar.get())
        },
    )
}

applyPublishingConfig(
    "ktAdvancements-runtime",
    withJavadoc = false,
    pom = {
        withXml {
            asNode().appendNode("dependencies").apply {
                rootProject.subprojects.forEach {
                    if (it.path.startsWith(":runtime:")) {
                        appendNode("dependency").apply {
                            appendNode("groupId", "dev.s7a")
                            appendNode("artifactId", "ktAdvancements-runtime-${it.name}")
                            appendNode("version", rootProject.version.toString())
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    },
)
