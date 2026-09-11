import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // buildSrc supplies Kotlin so it shares a classloader with the publishing and Dokka plugins.
    id("org.jetbrains.kotlin.jvm")
}

version = "1.0.0"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "kotlin")

    repositories {
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
