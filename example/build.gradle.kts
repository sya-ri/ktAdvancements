import dev.s7a.gradle.minecraft.server.tasks.LaunchMinecraftServerTask
import dev.s7a.gradle.minecraft.server.tasks.LaunchMinecraftServerTask.JarUrl
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.minecraft.server)
    alias(libs.plugins.shadow)
}

fun usesUnobfuscatedJar(versionName: String): Boolean {
    val versionParts = versionName.split('_').map(String::toInt)
    return versionParts[0] > 26 ||
        (versionParts[0] == 26 && versionParts[1] >= 1)
}

repositories {
    mavenLocal()
}

listOf("runtimeClasspath", "testRuntimeClasspath").forEach {
    configurations.named(it) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}

dependencies {
    compileOnly(libs.spigot.api)

    implementation(project(":api"))
    rootProject.subprojects
        .filter { it.path.startsWith(":runtime:") }
        .forEach {
            val versionName = it.name.drop(1)
            runtimeOnly(
                project(
                    mapOf(
                        "path" to it.path,
                        "configuration" to if (usesUnobfuscatedJar(versionName)) "default" else "reobf",
                    ),
                ),
            )
        }
}

tasks["build"].dependsOn("shadowJar")

listOf(
    "17" to "1.17.1",
    "18" to "1.18",
    "18_1" to "1.18.1",
    "18_2" to "1.18.2",
    "19" to "1.19",
    "19_1" to "1.19.1",
    "19_2" to "1.19.2",
    "19_3" to "1.19.3",
    "19_4" to "1.19.4",
    "20" to "1.20",
    "20_1" to "1.20.1",
    "20_2" to "1.20.2",
    "20_3" to "1.20.3",
    "20_4" to "1.20.4",
    "20_6" to "1.20.6",
    "21" to "1.21",
    "21_1" to "1.21.1",
    "21_3" to "1.21.3",
    "21_4" to "1.21.4",
    "21_5" to "1.21.5",
    "21_6" to "1.21.6",
    "21_7" to "1.21.7",
    "21_8" to "1.21.8",
    "21_9" to "1.21.9",
    "21_10" to "1.21.10",
    "21_11" to "1.21.11",
    "26_1_1" to "26.1.1",
    "26_1_2" to "26.1.2",
    "26_2" to "26.2",
).forEach { (name, version) ->
    tasks.register<LaunchMinecraftServerTask>("testPlugin$name") {
        dependsOn("build")

        doFirst {
            copy {
                from(
                    layout.buildDirectory.asFile
                        .get()
                        .resolve("libs/${project.name}-all.jar"),
                )
                into(
                    layout.buildDirectory.asFile
                        .get()
                        .resolve("MinecraftServer$name/plugins"),
                )
            }
        }

        serverDirectory.set(
            layout.buildDirectory.asFile
                .get()
                .resolve("MinecraftServer$name")
                .absolutePath,
        )
        jarUrl.set(JarUrl.Paper(version))
        agreeEula.set(true)
    }
}
