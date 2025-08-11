subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    dependencies {
        implementation(project(":api"))

        compileOnly(rootProject.libs.spigot.api)
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    applyPublishingConfig(
        "ktAdvancements-store-${project.name}",
        publication = {
            from(components["kotlin"])
            artifact(sourceJar.get())
        },
    )
}
