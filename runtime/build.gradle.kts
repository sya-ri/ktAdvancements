import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    if ("1_20_5" <= name) {
        java {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        kotlin {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }

    repositories {
        // Paper
        maven(url = "https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        implementation(project(":api"))

        extensions.getByType<PaperweightUserDependenciesExtension>().paperDevBundle("${name.replace('_', '.')}-R0.1-SNAPSHOT")
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    tasks.assemble {
        dependsOn(tasks.named("reobfJar"))
    }

    applyPublishingConfig(
        "ktAdvancements-runtime-v$name",
        publication = {
            artifact(
                layout.buildDirectory.file(
                    "libs/${project.name}-${project.version}-reobf.jar",
                ),
            ) {
                // spigot-mapped
                builtBy(tasks.named("reobfJar"))
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
