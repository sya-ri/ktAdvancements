plugins {
    `maven-publish`
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    paperweight.paperDevBundle("1.17.1-R0.1-SNAPSHOT")

    api(project(":api"))
}

subprojects {
    apply(plugin = "io.papermc.paperweight.userdev")
    apply(plugin = "maven-publish")

    val (major, minor, patch) = project.name.drop(1).split('_')

    dependencies {
        implementation(project(":"))
        implementation(project(":runtime"))

        paperweight.paperDevBundle("$major.$minor.$patch-R0.1-SNAPSHOT")
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
                artifact(tasks.reobfJar)
                artifact(sourceJar.get())
                artifact(tasks.jar).classifier = "mojang-mapped"
            }
        }
    }
}

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.s7a"
            artifactId = "ktAdvancements-runtime"
            version = rootProject.version.toString()
            from(components["kotlin"])
            artifact(tasks.jar).classifier = "mojang-mapped"
        }
    }
}
