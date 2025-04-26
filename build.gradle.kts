plugins {
    kotlin("jvm") version libs.versions.kotlin
}

group = "dev.s7a"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()

    // Spigot
    maven(url = "https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Spigot API
    compileOnly(libs.spigot.api)
}
