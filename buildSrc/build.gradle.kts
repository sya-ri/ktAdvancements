plugins {
    `kotlin-dsl`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.maven.publish.plugin)
    implementation(libs.dokka.gradle.plugin)

    // buildSrc is a parent classloader: match minecraft-server 4.0.2's serialization runtime.
    // Otherwise Dokka's 1.6.0 shadows the JVM default methods used by the server downloader.
    runtimeOnly(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
