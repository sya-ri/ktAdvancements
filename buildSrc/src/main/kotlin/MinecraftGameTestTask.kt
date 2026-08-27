import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Starts one Minecraft server and waits for the ktAdvancements game-test plugin
 * to report the advancement progress values observed in client-bound packets.
 */
@DisableCachingByDefault(because = "Starts an external Minecraft server and verifies its live result")
abstract class MinecraftGameTestTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginJar: RegularFileProperty

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    /** Runtime package segment expected from the plugin, for example `v1_20_3`. */
    @get:Input
    abstract val expectedRuntime: Property<String>

    @get:Input
    abstract val timeoutSeconds: Property<Long>

    init {
        group = "verification"
        timeoutSeconds.convention(DEFAULT_TIMEOUT_SECONDS)
    }

    @TaskAction
    fun runGameTest() {
        val version = minecraftVersion.get().trim()
        val expectedRuntimeSegment = expectedRuntime.get().trim()
        val expectedRuntimeClass =
            "$RUNTIME_PACKAGE_PREFIX.$expectedRuntimeSegment.$RUNTIME_IMPLEMENTATION_CLASS"
        val timeout = timeoutSeconds.get()
        require(version.isNotEmpty()) { "minecraftVersion must not be blank" }
        require(expectedRuntimeSegment.matches(RUNTIME_SEGMENT_PATTERN)) {
            "expectedRuntime must be a runtime package segment such as v1_20_3: '$expectedRuntimeSegment'"
        }
        require(timeout > 0) { "timeoutSeconds must be greater than zero" }

        val serverPath = serverJar.get().asFile.toPath().toAbsolutePath().normalize()
        val sourcePluginPath = pluginJar.get().asFile.toPath().toAbsolutePath().normalize()
        val javaPath = javaLauncher.get().executablePath.asFile.toPath().toAbsolutePath().normalize()
        val workPath = workDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        validateRegularFile(serverPath, "serverJar")
        validateRegularFile(sourcePluginPath, "pluginJar")
        validateRegularFile(javaPath, "javaLauncher executable")
        validateWorkDirectory(workPath)

        val pluginsPath = workPath.resolve("plugins")
        val installedPluginPath = pluginsPath.resolve(PLUGIN_FILE_NAME)
        val resultPath = workPath.resolve(RESULT_FILE_NAME)
        val logPath = workPath.resolve(LOG_FILE_NAME)
        Files.createDirectories(pluginsPath)
        Files.deleteIfExists(resultPath)
        Files.deleteIfExists(logPath)
        if (sourcePluginPath != installedPluginPath) {
            Files.copy(sourcePluginPath, installedPluginPath, StandardCopyOption.REPLACE_EXISTING)
        }
        writeConfiguration(workPath)

        val command =
            listOf(
                javaPath.toString(),
                "-D$GAME_TEST_ENABLED_PROPERTY=true",
                "-D$RESULT_FILE_PROPERTY=${resultPath}",
                "-jar",
                serverPath.toString(),
                "nogui",
            )
        logger.lifecycle("Starting Minecraft {} game test with Java {}", version, javaPath)
        val process =
            try {
                ProcessBuilder(command)
                    .directory(workPath.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start()
            } catch (exception: Exception) {
                throw GradleException("Could not start Minecraft $version; log: $logPath", exception)
            }

        val finished =
            try {
                process.waitFor(timeout, TimeUnit.SECONDS)
            } catch (exception: InterruptedException) {
                stopProcess(process)
                Thread.currentThread().interrupt()
                throw GradleException("Interrupted while waiting for Minecraft $version; log: $logPath", exception)
            }
        if (!finished) {
            stopProcess(process)
            fail(version, "server did not stop within $timeout seconds", logPath)
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            fail(version, "server exited with code $exitCode", logPath)
        }

        val result = readResult(version, resultPath, logPath)
        requireResult(version, result, "status", PASSED_STATUS, logPath)
        requireResult(version, result, "runtime", expectedRuntimeClass, logPath)
        requireResult(version, result, "progress", EXPECTED_PROGRESS, logPath)
        val bukkitVersion = requireResult(version, result, "bukkitVersion", logPath)
        if (
            bukkitVersion != version &&
            !bukkitVersion.startsWith("$version-") &&
            !bukkitVersion.startsWith("$version.build.")
        ) {
            fail(
                version,
                "result property 'bukkitVersion' was '$bukkitVersion', expected release '$version'",
                logPath,
            )
        }

        logger.lifecycle(
            "Minecraft {} game test passed: runtime={}, progress={} (log: {})",
            version,
            expectedRuntimeClass,
            EXPECTED_PROGRESS,
            logPath,
        )
    }

    private fun validateWorkDirectory(path: Path) {
        require(path.parent != null) { "workDirectory must not be a file-system root: $path" }
        val projectPath = project.projectDir.toPath().toAbsolutePath().normalize()
        val rootProjectPath = project.rootProject.projectDir.toPath().toAbsolutePath().normalize()
        require(path != projectPath && path != rootProjectPath) {
            "workDirectory must not be a project root: $path"
        }
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "workDirectory is not a directory: $path" }
    }

    private fun validateRegularFile(
        path: Path,
        propertyName: String,
    ) {
        require(Files.isRegularFile(path)) { "$propertyName is not a regular file: $path" }
    }

    private fun writeConfiguration(workPath: Path) {
        Files.writeString(
            workPath.resolve("eula.txt"),
            "eula=true\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        Files.writeString(
            workPath.resolve("server.properties"),
            SERVER_PROPERTIES,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun stopProcess(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(FORCED_STOP_SECONDS, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private fun readResult(
        version: String,
        resultPath: Path,
        logPath: Path,
    ): Properties {
        if (!Files.isRegularFile(resultPath)) {
            fail(version, "game-test plugin did not write $RESULT_FILE_NAME", logPath)
        }
        return try {
            Properties().apply {
                Files.newInputStream(resultPath).use { load(it) }
            }
        } catch (exception: Exception) {
            throw GradleException(
                failureMessage(version, "could not read $resultPath: ${exception.message}", logPath),
                exception,
            )
        }
    }

    private fun requireResult(
        version: String,
        result: Properties,
        propertyName: String,
        logPath: Path,
    ): String =
        result.getProperty(propertyName)
            ?: fail(version, "result property '$propertyName' is missing", logPath)

    private fun requireResult(
        version: String,
        result: Properties,
        propertyName: String,
        expectedValue: String,
        logPath: Path,
    ) {
        val actualValue = requireResult(version, result, propertyName, logPath)
        if (actualValue != expectedValue) {
            val pluginError = result.getProperty("error")?.let { "; plugin error: $it" }.orEmpty()
            fail(
                version,
                "result property '$propertyName' was '$actualValue', expected '$expectedValue'$pluginError",
                logPath,
            )
        }
    }

    private fun fail(
        version: String,
        reason: String,
        logPath: Path,
    ): Nothing = throw GradleException(failureMessage(version, reason, logPath))

    private fun failureMessage(
        version: String,
        reason: String,
        logPath: Path,
    ): String {
        val tail = readLogTail(logPath)
        return buildString {
            append("Minecraft ")
            append(version)
            append(" game test failed: ")
            append(reason)
            append(". Log: ")
            append(logPath)
            if (tail.isNotEmpty()) {
                appendLine()
                appendLine("--- last $LOG_TAIL_LINES log lines ---")
                append(tail)
            }
        }
    }

    private fun readLogTail(logPath: Path): String {
        if (!Files.isRegularFile(logPath)) return ""
        return try {
            val lines = ArrayDeque<String>(LOG_TAIL_LINES)
            Files.newBufferedReader(logPath, StandardCharsets.UTF_8).useLines { source ->
                source.forEach { line ->
                    if (lines.size == LOG_TAIL_LINES) lines.removeFirst()
                    lines.addLast(line)
                }
            }
            lines.joinToString(System.lineSeparator())
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 180L
        const val GRACEFUL_STOP_SECONDS = 10L
        const val FORCED_STOP_SECONDS = 10L
        const val LOG_TAIL_LINES = 80
        const val PLUGIN_FILE_NAME = "ktAdvancements-game-test.jar"
        const val RESULT_FILE_NAME = "result.properties"
        const val LOG_FILE_NAME = "server.log"
        const val GAME_TEST_ENABLED_PROPERTY = "ktAdvancements.gameTest"
        const val RESULT_FILE_PROPERTY = "ktAdvancements.gameTest.resultFile"
        const val PASSED_STATUS = "passed"
        const val EXPECTED_PROGRESS = "0/10,3/10,10/10,9/10"
        const val RUNTIME_PACKAGE_PREFIX = "dev.s7a.ktAdvancements.runtime"
        const val RUNTIME_IMPLEMENTATION_CLASS = "KtAdvancementRuntimeImpl"
        val RUNTIME_SEGMENT_PATTERN = Regex("v\\d+(?:_\\d+)+")
        val SERVER_PROPERTIES =
            """
            online-mode=false
            server-ip=127.0.0.1
            server-port=0
            level-name=game-test-world
            max-players=1
            spawn-protection=0
            view-distance=2
            simulation-distance=2
            enable-command-block=false
            motd=ktAdvancements game test
            """.trimIndent() + "\n"
    }
}
