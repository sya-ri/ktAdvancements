import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

version = "1.0.0-SNAPSHOT"

subprojects {
    apply(plugin = "kotlin")
}

allprojects {
    repositories {
        mavenCentral()

        // Spigot
        maven(url = "https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    compileOnly(libs.spigot.api)

    api(project(":api"))
}

val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.s7a"
            artifactId = "ktAdvancements"
            version = rootProject.version.toString()
            from(components["kotlin"])
            artifact(sourceJar.get())
            artifact(tasks.jar).classifier = "mojang-mapped"
        }
    }
}
