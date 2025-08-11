import kotlin.text.get

plugins {
    `maven-publish`
    signing
}

dependencies {
    compileOnly(libs.spigot.api)
}

val sourceJar =
    tasks.register("sourceJar", Jar::class.java) {
        archiveClassifier.set("sources")
        from(layout.projectDirectory.dir("src/main/kotlin"))
    }

applyPublishingConfig(
    "ktAdvancements-api",
    publication = {
        from(components["kotlin"])
        artifact(sourceJar.get())
    },
)
