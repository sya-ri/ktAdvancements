plugins {
    `maven-publish`
}

dependencies {
    compileOnly(libs.spigot.api)
}

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.s7a"
            artifactId = "ktAdvancements-api"
            version = rootProject.version.toString()
            from(components["kotlin"])
            artifact(sourceJar.get())
        }
    }
}
