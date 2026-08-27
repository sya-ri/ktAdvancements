import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.s7a.gradle.minecraft.server.tasks.LaunchMinecraftServerTask.JarUrl
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

plugins {
    alias(libs.plugins.minecraft.server)
    alias(libs.plugins.shadow)
}

kotlin {
    compilerOptions {
        // Test code must run on Java 17 even when Gradle itself runs on Java 25.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

fun usesUnobfuscatedJar(versionName: String): Boolean {
    val versionParts = versionName.split('_').map(String::toInt)
    return versionParts[0] > 26 ||
        (versionParts[0] == 26 && versionParts[1] >= 1)
}

listOf("runtimeClasspath", "testRuntimeClasspath").forEach {
    configurations.named(it) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}

val mojangRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // Share the test/API dependencies, but not runtimeOnly's Spigot-mapped runtimes.
    extendsFrom(configurations.implementation.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

dependencies {
    compileOnly(libs.spigot.api)

    implementation(project(":api"))
    implementation(libs.mockito.core)
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
            // These are the same normal JARs published as mojang-mapped through 1.21.11,
            // or without a classifier from 26.1 onward, by runtime-mojang.
            add(
                mojangRuntimeClasspath.name,
                project(mapOf("path" to it.path, "configuration" to "default")),
            )
        }
}

val mojangShadowJar by tasks.registering(ShadowJar::class) {
    description = "Packages all current runtimes in the Mojang namespace for modern Paper."
    archiveClassifier.set("mojang-all")
    from(sourceSets.named("main").map { it.output })
    configurations.set(setOf(mojangRuntimeClasspath))
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    // Match Shadow's standard archive-metadata exclusions, retaining every runtime
    // and all multi-release implementation classes (including Byte Buddy's).
    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/versions/**/module-info.class",
        "module-info.class",
    )
}

tasks.build {
    dependsOn(tasks.shadowJar, mojangShadowJar)
}

fun usesMojangMappedPaper(versionName: String): Boolean {
    val parts = versionName.split('_').map(String::toInt)
    return parts[0] > 1 ||
        (parts[0] == 1 && (parts[1] > 20 || (parts[1] == 20 && parts.getOrElse(2) { 0 } >= 5)))
}

fun gameTestJavaVersion(versionName: String): Int {
    val parts = versionName.split('_').map(String::toInt)
    return when {
        parts[0] >= 26 -> 25
        parts[0] > 1 || parts[1] > 20 || (parts[1] == 20 && parts.getOrElse(2) { 0 } >= 5) -> 21
        else -> 17
    }
}

val runtimeProjects =
    rootProject.subprojects
        .filter { it.path.startsWith(":runtime:") }
        .sortedWith(
            compareBy(
                { it.name.drop(1).split('_')[0].toInt() },
                { it.name.drop(1).split('_')[1].toInt() },
                { it.name.drop(1).split('_').getOrElse(2) { "0" }.toInt() },
            ),
        )

val downloadBuildTools by tasks.registering(DownloadGameTestFile::class) {
    url.set("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar")
    destination.set(layout.buildDirectory.file("tools/BuildTools.jar"))
}

val gameTestAll by tasks.registering {
    group = "verification"
    description = "Runs advancement packet game tests on every supported Minecraft server."
}

val screenshotTestAll by tasks.registering {
    group = "verification"
    description = "Compares all four advancement progress screenshots against committed baselines on every supported client."
}

// CI builds both namespace variants once, retaining all 30 runtime implementations.
val testPluginJar =
    providers.gradleProperty("gameTestPluginJar")
        .let { override ->
            if (override.isPresent) layout.file(override.map { file(it) })
            else tasks.shadowJar.flatMap { it.archiveFile }
        }
val testMojangPluginJar =
    providers.gradleProperty("gameTestMojangPluginJar")
        .let { override ->
            if (override.isPresent) layout.file(override.map { file(it) })
            else mojangShadowJar.flatMap { it.archiveFile }
        }

data class PortableMcPlatform(val archive: String, val checksum: String, val executable: String)

val portableMcVersion = "5.0.4"
val portableMcPlatform = providers.provider {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("windows") && arch in setOf("amd64", "x86_64") ->
            PortableMcPlatform(
                "windows-x86_64-msvc.zip",
                "4482bb5325f9e09573ebe578ff5e9984e5205c45962cbc08d2524e1b51ab6315",
                "portablemc.exe",
            )
        os.contains("linux") && arch in setOf("amd64", "x86_64") ->
            PortableMcPlatform(
                "linux-x86_64-gnu.tar.gz",
                "b14d2dff5191dabf90414562820ffdddfb5ee1acf692729782b4691d55b7b4f8",
                "portablemc",
            )
        else -> error("Screenshot tests support Linux x86_64 and Windows x86_64; got $os/$arch")
    }
}
val downloadPortableMc by tasks.registering(DownloadGameTestFile::class) {
    val archiveName = portableMcPlatform.map { "portablemc-$portableMcVersion-${it.archive}" }
    url.set(archiveName.map { "https://github.com/theorzr/portablemc/releases/download/v$portableMcVersion/$it" })
    sha256.set(portableMcPlatform.map { it.checksum })
    destination.set(layout.buildDirectory.file(archiveName.map { "tools/$it" }))
}
val portableMcDirectory = layout.buildDirectory.dir("tools/portablemc-$portableMcVersion")
val installPortableMc by tasks.registering(Copy::class) {
    from(downloadPortableMc.flatMap { it.destination }.map {
        if (it.asFile.extension == "zip") zipTree(it.asFile)
        else tarTree(resources.gzip(it.asFile))
    })
    include("**/portablemc", "**/portablemc.exe", "**/LICENSE")
    eachFile { path = name }
    includeEmptyDirs = false
    into(portableMcDirectory)
    doLast {
        val executable = portableMcDirectory.get().file(portableMcPlatform.get().executable).asFile
        check(executable.setExecutable(true) || executable.canExecute()) {
            "Could not make PortableMC executable: $executable"
        }
    }
}
val installedPortableMcExecutable = installPortableMc.map {
    portableMcDirectory.get().file(portableMcPlatform.get().executable)
}
val screenshotDriverMode = providers.gradleProperty("gameTestScreenshotDriver")
    .orElse(if (System.getProperty("os.name").lowercase().contains("linux")) "linux" else "manual")
val updateScreenshotBaselines = providers.gradleProperty("updateGameTestScreenshots")
    .map { it.toBooleanStrict() }
    .orElse(false)

val gameTestVersions by tasks.registering {
    group = "verification"
    description = "Prints the complete Minecraft game-test matrix as JSON."
    doLast {
        println(runtimeProjects.joinToString(prefix = "[", postfix = "]") { "\"${it.name.drop(1)}\"" })
    }
}

runtimeProjects.forEach { runtimeProject ->
    val versionName = runtimeProject.name.drop(1)
    val version = versionName.replace('_', '.')
    val javaVersion = gameTestJavaVersion(versionName)
    val launcher =
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
    // These exact releases have no Paper server distribution.
    val spigotRevision = mapOf("1_20_3" to "3961", "26_1" to "4608")[versionName]
    val selectedPluginJar =
        if (spigotRevision == null && usesMojangMappedPaper(versionName)) testMojangPluginJar
        else testPluginJar
    val serverJarProvider: Provider<RegularFile> =
        if (spigotRevision == null) {
            tasks.register<DownloadGameTestFile>("downloadPaper$versionName") {
                url.set(providers.provider { JarUrl.Paper(version).get() })
                destination.set(layout.buildDirectory.file("servers/paper-$version.jar"))
            }.flatMap { it.destination }
        } else {
            val overrideJar = providers.gradleProperty("gameTestSpigot${versionName}Jar")
            if (overrideJar.isPresent) {
                layout.file(overrideJar.map { file(it) })
            } else {
                tasks.register<BuildSpigotGameTestServer>("buildSpigot$versionName") {
                    revision.set(spigotRevision)
                    buildToolsJar.set(downloadBuildTools.flatMap { it.destination })
                    javaLauncher.set(launcher)
                    workDirectory.set(layout.buildDirectory.dir("buildtools/$version"))
                    serverJar.set(layout.buildDirectory.file("servers/spigot-$version.jar"))
                }.flatMap { it.serverJar }
            }
        }
    val gameTest =
        tasks.register<MinecraftGameTestTask>("gameTest$versionName") {
            description = "Verifies advancement packets on Minecraft $version."
            minecraftVersion.set(version)
            expectedRuntime.set(runtimeProject.name)
            serverJar.set(serverJarProvider)
            pluginJar.set(selectedPluginJar)
            javaLauncher.set(launcher)
            workDirectory.set(layout.buildDirectory.dir("servers/run-$version"))
        }
    gameTestAll.configure { dependsOn(gameTest) }

    val screenshotTest =
        tasks.register<MinecraftScreenshotTestTask>("screenshotTest$versionName") {
            description = "Compares advancement progress screenshots on Minecraft $version with committed baselines."
            dependsOn(gameTest)
            minecraftVersion.set(version)
            expectedRuntime.set(runtimeProject.name)
            serverJar.set(serverJarProvider)
            pluginJar.set(selectedPluginJar)
            javaLauncher.set(launcher)
            portableMcExecutable.set(installedPortableMcExecutable)
            clientCacheDirectory.set(layout.buildDirectory.dir("client-cache"))
            workDirectory.set(layout.buildDirectory.dir("visual/$version"))
            baselineDirectory.set(layout.projectDirectory.dir("src/test/screenshots"))
            updateBaselines.set(updateScreenshotBaselines)
            when (screenshotDriverMode.get()) {
                "linux" -> screenshotDriver.set(layout.projectDirectory.file("scripts/capture-linux.py"))
                "manual" -> Unit
                else -> error("gameTestScreenshotDriver must be linux or manual")
            }
        }
    screenshotTestAll.configure { dependsOn(screenshotTest) }
}
