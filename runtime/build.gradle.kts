import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    `maven-publish`
    signing
    alias(libs.plugins.paperweight.userdev) apply false
}

fun isPaperOnlyRuntime(versionName: String) = "26_1" <= versionName

subprojects {
    apply(plugin = "io.papermc.paperweight.userdev")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val name = project.name.drop(1)
    val paperOnlyRuntime = isPaperOnlyRuntime(name)
    val jvmVersion =
        when {
            paperOnlyRuntime -> 25
            "1_20_5" <= name -> 21
            else -> 17
        }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion))
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }

    kotlin {
        jvmToolchain(jvmVersion)
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmVersion.toString()))
        }
    }

    repositories {
        // Paper
        maven(url = "https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        implementation(project(":api"))

        extensions.getByType<PaperweightUserDependenciesExtension>().paperDevBundle(
            if (paperOnlyRuntime) {
                "${name.replace('_', '.')}.build.+"
            } else {
                "${name.replace('_', '.')}-R0.1-SNAPSHOT"
            },
        )
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    tasks.named("reobfJar") {
        // Paper-only runtimes do not ship reobf mappings, so this task must stay out of the graph.
        enabled = paperOnlyRuntime.not()
    }

    tasks.assemble {
        dependsOn(
            if (paperOnlyRuntime) {
                tasks.named("jar")
            } else {
                tasks.named("reobfJar")
            },
        )
    }

    applyPublishingConfig(
        "ktAdvancements-runtime-v$name",
        publication = {
            if (paperOnlyRuntime) {
                // 26.1+ no longer has a Spigot-mapped production artifact.
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
            }
            artifact(sourceJar.get())
            artifact(tasks.jar) {
                classifier = "mojang-mapped"
            }
        },
    )
}

applyPublishingConfig(
    "ktAdvancements-runtime",
    pom = {
        withXml {
            asNode().appendNode("dependencies").apply {
                rootProject.subprojects.forEach {
                    if (it.path.startsWith(":runtime:")) {
                        val versionName = it.name.drop(1)
                        appendNode("dependency").apply {
                            appendNode("groupId", "dev.s7a")
                            appendNode("artifactId", "ktAdvancements-runtime-${it.name}")
                            appendNode("version", rootProject.version.toString())
                            if (isPaperOnlyRuntime(versionName)) {
                                // 26.1+ is Paper-only even in the generic runtime aggregate.
                                appendNode("classifier", "mojang-mapped")
                            }
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    },
)
