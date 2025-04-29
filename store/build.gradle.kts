subprojects {
    apply(plugin = "maven-publish")

    repositories {
        // Spigot
        maven(url = "https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }

    dependencies {
        implementation(project(":api"))

        compileOnly(rootProject.libs.spigot.api)
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                groupId = "dev.s7a"
                artifactId = "ktAdvancements-store-${project.name}"
                version = rootProject.version.toString()
                from(components["kotlin"])
                artifact(sourceJar.get())
            }
        }
    }
}
