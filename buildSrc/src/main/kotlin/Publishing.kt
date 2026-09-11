import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask

fun Project.applyPublishingConfig(
    publishName: String,
    withJavadoc: Boolean = true,
    publication: MavenPublication.() -> Unit = {},
    pom: MavenPom.() -> Unit = {},
) {
    version = rootProject.version
    // The base plugin leaves our manually assembled runtime publications unchanged.
    pluginManager.apply("com.vanniktech.maven.publish.base")

    val javadocJar =
        if (withJavadoc) {
            pluginManager.apply("org.jetbrains.dokka")
            pluginManager.apply("org.jetbrains.dokka-javadoc")
            val documentation =
                tasks.named("dokkaGeneratePublicationJavadoc", DokkaGeneratePublicationTask::class.java)
            tasks.register("javadocJar", Jar::class.java) {
                archiveClassifier.set("javadoc")
                from(documentation.flatMap { it.outputDirectory })
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
        } else {
            null
        }

    extensions.configure(PublishingExtension::class.java) {
        repositories {
            maven {
                name = "ReleaseValidation"
                url = rootProject.layout.buildDirectory.dir("release-repository").get().asFile.toURI()
            }
        }
        publications {
            register("maven", MavenPublication::class.java) {
                groupId = "dev.s7a"
                artifactId = publishName
                publication()
                javadocJar?.let { artifact(it) }

                pom {
                    name.set(publishName)
                    description.set(
                        "A lightweight, packet-based Minecraft advancements library for Spigot/Paper plugins with customizable runtime and data storage.",
                    )
                    url.set("https://github.com/sya-ri/ktAdvancements")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/sya-ri/ktAdvancements/blob/master/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("sya-ri")
                            name.set("sya-ri")
                            email.set("contact@s7a.dev")
                        }
                    }
                    scm {
                        url.set("https://github.com/sya-ri/ktAdvancements")
                        connection.set("scm:git:https://github.com/sya-ri/ktAdvancements.git")
                        developerConnection.set("scm:git:ssh://git@github.com/sya-ri/ktAdvancements.git")
                        tag.set(providers.gradleProperty("releaseCommit").orElse("v${project.version}"))
                    }
                    pom()
                }
            }
        }
    }
    extensions.configure(MavenPublishBaseExtension::class.java) {
        coordinates("dev.s7a", publishName, project.version.toString())
        publishToMavenCentral()
        signAllPublications()
    }

    addToRootPublishingTask(
        "publishAllPublicationsToReleaseValidationRepository",
        "Stages every publication in the local release-validation repository without uploading it.",
    )
    addToRootPublishingTask(
        "publishAndReleaseToMavenCentral",
        "Publishes and releases every publication in one Maven Central deployment.",
    )
}

private fun Project.addToRootPublishingTask(
    taskName: String,
    taskDescription: String,
) {
    val publicationTask = tasks.named(taskName)
    val aggregateTask =
        if (taskName in rootProject.tasks.names) {
            rootProject.tasks.named(taskName)
        } else {
            rootProject.tasks.register(taskName) {
                group = "publishing"
                description = taskDescription
            }
        }
    // One Gradle invocation lets the plugin's shared build service bundle all modules together.
    aggregateTask.configure { dependsOn(publicationTask) }
}
