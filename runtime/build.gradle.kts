import io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension

plugins {
    `maven-publish` apply false
    alias(libs.plugins.paperweight.userdev) apply false
}

subprojects {
    apply(plugin = "io.papermc.paperweight.userdev")
    apply(plugin = "maven-publish")

    val (major, minor, patch) = project.name.drop(1).split('_')

    dependencies {
        implementation(project(":"))
        implementation(project(":runtime-api"))

        extensions.getByType<PaperweightUserDependenciesExtension>().paperDevBundle("$major.$minor.$patch-R0.1-SNAPSHOT")
    }

    val sourceJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = "dev.s7a"
                artifactId = "ktAdvancements-runtime-v${major}_${minor}_$patch"
                version = rootProject.version.toString()
                artifact(tasks.named("reobfJar"))
                artifact(sourceJar.get())
                artifact(tasks.jar).classifier = "mojang-mapped"
            }
        }
    }
}
