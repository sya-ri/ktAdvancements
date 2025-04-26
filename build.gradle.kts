plugins {
    kotlin("jvm") version libs.versions.kotlin
    `maven-publish`
    alias(libs.plugins.paperweight.userdev)
}

group = "dev.s7a"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.reobfJar)
            artifact(tasks.jar).classifier = "mojang-mapped"
        }
    }
}
