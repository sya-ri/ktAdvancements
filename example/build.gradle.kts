import dev.s7a.gradle.minecraft.server.tasks.LaunchMinecraftServerTask
import dev.s7a.gradle.minecraft.server.tasks.LaunchMinecraftServerTask.JarUrl

plugins {
    alias(libs.plugins.minecraft.server)
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(libs.spigot.api)

    implementation(project(":api"))
    implementation(project(":runtime"))
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
).forEach { (name, version) ->
    tasks.register<LaunchMinecraftServerTask>("testPlugin$name") {
        dependsOn("build")

        doFirst {
            copy {
                from(
                    layout.buildDirectory.asFile
                        .get()
                        .resolve("libs/${project.name}.jar"),
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
