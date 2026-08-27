import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.DisableCachingByDefault
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Runs a real client and server and collects an acknowledged screenshot of every visual test stage. */
@DisableCachingByDefault(because = "Starts an interactive Minecraft client and records live screenshots")
abstract class MinecraftScreenshotTestTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    /** Runtime package segment, such as `v1_20_3`. */
    @get:Input
    abstract val expectedRuntime: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginJar: RegularFileProperty

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val portableMcExecutable: RegularFileProperty

    /** Shared PortableMC downloads only; each run uses its own client game directory. */
    @get:Internal
    abstract val clientCacheDirectory: DirectoryProperty

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    /** Optional Linux-only driver; without it the task prompts the user to press F2. */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val screenshotDriver: RegularFileProperty

    @get:Input
    abstract val timeoutSeconds: Property<Long>

    @get:Input
    abstract val renderDelaySeconds: Property<Long>

    @get:Input
    abstract val joinTimeoutSeconds: Property<Long>

    @get:Input
    abstract val stageTimeoutSeconds: Property<Long>

    init {
        group = "verification"
        timeoutSeconds.convention(900L)
        renderDelaySeconds.convention(3L)
        joinTimeoutSeconds.convention(180L)
        stageTimeoutSeconds.convention(120L)
    }

    @TaskAction
    fun runScreenshotTest() {
        val configuration = configuration()
        val run = TestRun(configuration, deadlineAfter(configuration.timeoutSeconds))
        var failure: Throwable? = null
        try {
            prepareDirectories(configuration)
            logger.lifecycle("Installing Minecraft {} client files with PortableMC --dry", configuration.version)
            val installer =
                startProcess(
                    run,
                    "PortableMC installation",
                    portableMcCommand(configuration) + "--dry",
                    configuration.clientLog,
                )
            waitForExit(run, installer)
            checkExitCode(installer)

            val port = availableLoopbackPort()
            writeServerConfiguration(configuration, port)
            run.server =
                startProcess(
                    run,
                    "Minecraft server",
                    serverCommand(configuration),
                    configuration.serverLog,
                )
            waitForServerReady(run, port)
            run.client =
                startProcess(
                    run,
                    "Minecraft client",
                    portableMcCommand(configuration) +
                        listOf("--join-server", LOOPBACK_ADDRESS, "--join-server-port", port.toString()),
                    configuration.clientLog,
                    appendLog = true,
                )

            STAGES.forEachIndexed { index, stage ->
                val stageDeadline = waitForStage(run, stage, firstStage = index == 0)
                val delay =
                    if (index == 0) {
                        maxOf(INITIAL_RENDER_DELAY_SECONDS, configuration.renderDelaySeconds)
                    } else {
                        configuration.renderDelaySeconds
                    }
                waitForRendering(run, stageDeadline, delay)
                val previousScreenshots = listScreenshots(configuration.clientScreenshots).toSet()
                if (configuration.driverPath != null) {
                    val driver =
                        startProcess(
                            run,
                            "Screenshot driver (${stage.name})",
                            listOf(
                                "python3",
                                configuration.driverPath.toString(),
                                "--stage",
                                stage.name,
                                "--client-log",
                                configuration.minecraftClientLog.toString(),
                                "--version",
                                configuration.version,
                            ),
                            configuration.driverLog,
                            appendLog = true,
                        )
                    waitForExit(run, driver, stageDeadline, monitorGameProcesses = true)
                    checkExitCode(driver)
                } else {
                    logger.lifecycle(
                        "Minecraft {} stage {} ({}): open Advancements, hover Progress, then press F2.",
                        configuration.version,
                        stage.name,
                        stage.progress,
                    )
                }

                val screenshot = waitForScreenshot(run, stage, previousScreenshots, stageDeadline)
                copyScreenshotAtomically(screenshot, configuration.exchangeScreenshots.resolve(stage.screenshot), stage)
                Files.createFile(configuration.exchangeDirectory.resolve("ack-${stage.name}"))
                logger.lifecycle("Captured Minecraft {} stage {}: {}", configuration.version, stage.name, screenshot)
            }

            val server = requireNotNull(run.server)
            waitForExit(run, server)
            checkExitCode(server)
            val result = readProperties(configuration.resultFile)
            validateResult(configuration, result)
            result.setProperty("captureMode", configuration.captureMode)
            writePropertiesAtomically(configuration.resultFile, result)
            logger.lifecycle(
                "Minecraft {} screenshot test passed ({}): {}",
                configuration.version,
                configuration.captureMode,
                configuration.exchangeScreenshots,
            )
        } catch (throwable: Throwable) {
            failure = throwable
            throw GradleException(failureMessage(configuration, throwable), throwable)
        } finally {
            val wasInterrupted = Thread.interrupted()
            run.processes.asReversed().forEach(::stopOwnedProcess)
            if (failure != null) {
                recordFailure(configuration, failure)
            }
            if (wasInterrupted || failure is InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun configuration(): Configuration {
        val version = minecraftVersion.get().trim()
        val runtime = expectedRuntime.get().trim()
        require(version.isNotEmpty()) { "minecraftVersion must not be blank" }
        require(runtime.matches(Regex("v\\d+(?:_\\d+)+"))) {
            "expectedRuntime must be a runtime package segment such as v1_20_3"
        }
        val timeout = timeoutSeconds.get()
        val renderDelay = renderDelaySeconds.get()
        val joinTimeout = joinTimeoutSeconds.get()
        val stageTimeout = stageTimeoutSeconds.get()
        require(timeout > 0 && joinTimeout > 0 && stageTimeout > 0) { "Timeout values must be greater than zero" }
        require(renderDelay >= 0) { "renderDelaySeconds must not be negative" }
        listOf(timeout, renderDelay, joinTimeout, stageTimeout).forEach {
            require(it <= Long.MAX_VALUE / NANOS_PER_SECOND) { "Timeout value is too large: $it" }
        }

        val workPath = workDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val projectPath = project.projectDir.toPath().toAbsolutePath().normalize()
        val rootProjectPath = project.rootProject.projectDir.toPath().toAbsolutePath().normalize()
        require(workPath.parent != null && workPath != projectPath && workPath != rootProjectPath) {
            "workDirectory must be a dedicated directory, not a file-system or project root: $workPath"
        }
        val driverPath = screenshotDriver.orNull?.asFile?.toPath()?.toAbsolutePath()?.normalize()
        require(driverPath == null || System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")) {
            "screenshotDriver is supported only on Linux; omit it to use manual F2 capture"
        }
        val configuration =
            Configuration(
                version = version,
                runtimeClass = "dev.s7a.ktAdvancements.runtime.$runtime.KtAdvancementRuntimeImpl",
                serverPath = serverJar.get().asFile.toPath().toAbsolutePath().normalize(),
                pluginPath = pluginJar.get().asFile.toPath().toAbsolutePath().normalize(),
                javaPath = javaLauncher.get().executablePath.asFile.toPath().toAbsolutePath().normalize(),
                portableMcPath = portableMcExecutable.get().asFile.toPath().toAbsolutePath().normalize(),
                cachePath = clientCacheDirectory.get().asFile.toPath().toAbsolutePath().normalize(),
                workPath = workPath,
                driverPath = driverPath,
                timeoutSeconds = timeout,
                renderDelaySeconds = renderDelay,
                joinTimeoutSeconds = joinTimeout,
                stageTimeoutSeconds = stageTimeout,
            )
        listOf(configuration.serverPath, configuration.pluginPath, configuration.javaPath, configuration.portableMcPath)
            .plus(listOfNotNull(driverPath))
            .forEach { require(Files.isRegularFile(it)) { "Required executable, JAR, or driver is missing: $it" } }
        require(configuration.cachePath != configuration.clientDirectory) {
            "clientCacheDirectory must not be the isolated client game directory"
        }
        return configuration
    }

    private fun prepareDirectories(configuration: Configuration) {
        Files.createDirectories(configuration.workPath)
        Files.createDirectories(configuration.cachePath)
        Files.createDirectories(configuration.clientScreenshots)
        Files.createDirectories(configuration.exchangeScreenshots)
        val pluginsDirectory = configuration.workPath.resolve("plugins")
        Files.createDirectories(pluginsDirectory)
        val installedPlugin = pluginsDirectory.resolve("ktAdvancements-game-test.jar")
        if (configuration.pluginPath != installedPlugin) {
            Files.copy(configuration.pluginPath, installedPlugin, StandardCopyOption.REPLACE_EXISTING)
        }

        // These are this task's exact protocol/output files; shared assets and client screenshots are retained.
        listOf(
            configuration.resultFile,
            configuration.stageFile,
            configuration.serverLog,
            configuration.clientLog,
            configuration.driverLog,
        ).forEach(Files::deleteIfExists)
        STAGES.forEach { stage ->
            Files.deleteIfExists(configuration.exchangeDirectory.resolve("ack-${stage.name}"))
            Files.deleteIfExists(configuration.exchangeScreenshots.resolve(stage.screenshot))
        }
        Files.writeString(configuration.clientDirectory.resolve("options.txt"), CLIENT_OPTIONS, StandardCharsets.UTF_8)
        Files.writeString(configuration.workPath.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8)
    }

    private fun writeServerConfiguration(
        configuration: Configuration,
        port: Int,
    ) {
        val properties =
            """
            online-mode=false
            server-ip=$LOOPBACK_ADDRESS
            server-port=$port
            level-name=screenshot-game-test-world
            level-type=flat
            generate-structures=false
            spawn-monsters=false
            spawn-animals=false
            spawn-npcs=false
            difficulty=peaceful
            gamemode=creative
            force-gamemode=true
            allow-nether=false
            max-players=1
            spawn-protection=0
            view-distance=2
            simulation-distance=2
            enable-command-block=false
            motd=ktAdvancements screenshot test
            """.trimIndent() + "\n"
        Files.writeString(configuration.workPath.resolve("server.properties"), properties, StandardCharsets.UTF_8)
    }

    private fun portableMcCommand(configuration: Configuration) =
        listOf(
            configuration.portableMcPath.toString(),
            "--output",
            "machine",
            "--main-dir",
            configuration.cachePath.toString(),
            "start",
            configuration.version,
            "--mc-dir",
            configuration.clientDirectory.toString(),
            "--jvm",
            configuration.javaPath.toString(),
            "--jvm-arg=-Xms256M,-Xmx2G",
            "--resolution",
            "${SCREENSHOT_WIDTH}x$SCREENSHOT_HEIGHT",
            "--username",
            "KtGameTest",
        )

    private fun serverCommand(configuration: Configuration) =
        listOf(
            configuration.javaPath.toString(),
            "-Xms512M",
            "-Xmx2G",
            "-DktAdvancements.gameTest=true",
            "-DktAdvancements.gameTest.mode=visual",
            "-DktAdvancements.gameTest.resultFile=${configuration.resultFile}",
            "-DktAdvancements.gameTest.exchangeDirectory=${configuration.exchangeDirectory}",
            "-DktAdvancements.gameTest.joinTimeoutSeconds=${configuration.joinTimeoutSeconds}",
            "-DktAdvancements.gameTest.stageTimeoutSeconds=${configuration.stageTimeoutSeconds}",
            "-jar",
            configuration.serverPath.toString(),
            "nogui",
        )

    private fun startProcess(
        run: TestRun,
        name: String,
        command: List<String>,
        logPath: Path,
        appendLog: Boolean = false,
    ): OwnedProcess {
        checkDeadline(run)
        val process =
            ProcessBuilder(command)
                .directory(run.configuration.workPath.toFile())
                .apply {
                    if (run.configuration.driverPath != null) {
                        // Keep GLFW inside the selected X11/Xvfb display, including on WSLg.
                        environment().remove("WAYLAND_DISPLAY")
                        environment()["XDG_SESSION_TYPE"] = "x11"
                    }
                }
                .redirectErrorStream(true)
                .redirectOutput(
                    if (appendLog) ProcessBuilder.Redirect.appendTo(logPath.toFile()) else ProcessBuilder.Redirect.to(logPath.toFile()),
                ).start()
        return OwnedProcess(name, process).also {
            run.processes += it
            it.refreshDescendants()
        }
    }

    private fun waitForExit(
        run: TestRun,
        owned: OwnedProcess,
        localDeadline: Long? = null,
        monitorGameProcesses: Boolean = false,
    ) {
        while (owned.isAlive()) {
            checkDeadline(run, localDeadline)
            if (monitorGameProcesses) checkGameProcesses(run)
            pause(run)
        }
    }

    private fun checkExitCode(owned: OwnedProcess) {
        check(owned.process.exitValue() == 0) { "${owned.name} exited with code ${owned.process.exitValue()}" }
    }

    private fun availableLoopbackPort(): Int =
        ServerSocket(0, 0, InetAddress.getByName(LOOPBACK_ADDRESS)).use { it.localPort }

    private fun waitForServerReady(
        run: TestRun,
        port: Int,
    ) {
        while (true) {
            checkDeadline(run)
            checkGameProcesses(run)
            val started =
                Files.isRegularFile(run.configuration.serverLog) &&
                    Files.newBufferedReader(run.configuration.serverLog, StandardCharsets.UTF_8).useLines { lines ->
                        lines.any { it.contains("Done (") }
                    }
            val listening =
                started &&
                    try {
                        Socket().use { it.connect(InetSocketAddress(LOOPBACK_ADDRESS, port), SOCKET_TIMEOUT_MILLIS) }
                        true
                    } catch (_: IOException) {
                        false
                    }
            if (listening) return
            pause(run)
        }
    }

    private fun waitForStage(
        run: TestRun,
        expected: Stage,
        firstStage: Boolean,
    ): Long {
        val timeout =
            if (firstStage) run.configuration.joinTimeoutSeconds else run.configuration.stageTimeoutSeconds
        val stageDeadline = deadlineAfter(timeout)
        while (true) {
            checkDeadline(run, stageDeadline, "waiting for stage ${expected.name}")
            checkGameProcesses(run)
            if (Files.isRegularFile(run.configuration.stageFile)) {
                val properties = readProperties(run.configuration.stageFile)
                val stage = properties.getProperty("stage")
                if (stage == expected.name) {
                    check(properties.getProperty("progress") == expected.progress) {
                        "Stage ${expected.name} reported progress '${properties.getProperty("progress")}', expected '${expected.progress}'"
                    }
                    return deadlineAfter(run.configuration.stageTimeoutSeconds)
                }
                val actualIndex = STAGES.indexOfFirst { it.name == stage }
                check(actualIndex >= 0 && actualIndex < STAGES.indexOf(expected)) {
                    "Expected stage ${expected.name}, got '$stage'"
                }
            }
            pause(run)
        }
    }

    private fun waitForRendering(
        run: TestRun,
        stageDeadline: Long,
        seconds: Long,
    ) {
        val renderDeadline = deadlineAfter(seconds)
        while (!hasExpired(renderDeadline)) {
            checkDeadline(run, stageDeadline, "waiting for client rendering")
            checkGameProcesses(run)
            pause(run)
        }
    }

    private fun waitForScreenshot(
        run: TestRun,
        stage: Stage,
        previousScreenshots: Set<Path>,
        stageDeadline: Long,
    ): Path {
        val observations = mutableMapOf<Path, Pair<Long, Long>>()
        var lastDecodeFailure: String? = null
        while (true) {
            checkDeadline(
                run,
                stageDeadline,
                "waiting for a new ${stage.name} PNG${lastDecodeFailure?.let { ": $it" }.orEmpty()}",
            )
            checkGameProcesses(run)
            val candidates =
                listScreenshots(run.configuration.clientScreenshots)
                    .filterNot(previousScreenshots::contains)
                    .sortedBy { Files.getLastModifiedTime(it).toMillis() }
            candidates.forEach { candidate ->
                val observation = Files.size(candidate) to Files.getLastModifiedTime(candidate).toMillis()
                val previous = observations.put(candidate, observation)
                if (observation.first > 0L && previous == observation) {
                    try {
                        validateScreenshot(candidate, stage)
                        return candidate
                    } catch (exception: IOException) {
                        // F2 can expose its output path while the PNG encoder is still writing.
                        lastDecodeFailure = exception.message
                    }
                }
            }
            pause(run)
        }
    }

    private fun listScreenshots(directory: Path): List<Path> =
        Files.list(directory).use { files ->
            files.filter {
                Files.isRegularFile(it) && it.fileName.toString().lowercase(Locale.ROOT).endsWith(".png")
            }.toList()
        }

    private fun validateScreenshot(path: Path, stage: Stage) {
        val signature = Files.newInputStream(path).use { it.readNBytes(PNG_SIGNATURE.size) }
        if (!signature.contentEquals(PNG_SIGNATURE)) throw IOException("Not a complete PNG: $path")
        val image = ImageIO.read(path.toFile()) ?: throw IOException("Could not decode PNG: $path")
        try {
            AdvancementScreenshotValidator.validate(image, stage.completed)
        } catch (exception: IllegalStateException) {
            throw IllegalStateException("Screenshot $path: ${exception.message}", exception)
        } finally {
            image.flush()
        }
    }

    private fun copyScreenshotAtomically(
        source: Path,
        destination: Path,
        stage: Stage,
    ) {
        val temporary = Files.createTempFile(destination.parent, ".capture-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            validateScreenshot(temporary, stage)
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkGameProcesses(run: TestRun) {
        run.server?.let { check(it.isAlive()) { "Minecraft server stopped before the screenshot test finished" } }
        run.client?.let { check(it.isAlive()) { "Minecraft client stopped before the screenshot test finished" } }
    }

    private fun checkDeadline(
        run: TestRun,
        localDeadline: Long? = null,
        operation: String = "waiting for the process",
    ) {
        check(!hasExpired(run.deadline)) { "Screenshot test exceeded ${run.configuration.timeoutSeconds} seconds" }
        check(localDeadline == null || !hasExpired(localDeadline)) { "Timed out $operation" }
    }

    private fun pause(run: TestRun) {
        run.processes.forEach(OwnedProcess::refreshDescendants)
        TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
    }

    private fun validateResult(
        configuration: Configuration,
        result: Properties,
    ) {
        val expected =
            mapOf(
                "status" to "passed",
                "runtime" to configuration.runtimeClass,
                "progress" to STAGES.joinToString(",", transform = Stage::progress),
                "screenshots" to STAGES.joinToString(",", transform = Stage::screenshot),
            )
        expected.forEach { (property, value) ->
            check(result.getProperty(property) == value) {
                "Result property '$property' was '${result.getProperty(property)}', expected '$value'. ${result.getProperty("error").orEmpty()}"
            }
        }
        val bukkitVersion = result.getProperty("bukkitVersion", "")
        check(
            bukkitVersion == configuration.version ||
                bukkitVersion.startsWith("${configuration.version}-") ||
                bukkitVersion.startsWith("${configuration.version}.build."),
        ) {
            "Unexpected Bukkit version: ${result.getProperty("bukkitVersion")}"
        }
        STAGES.forEach { validateScreenshot(configuration.exchangeScreenshots.resolve(it.screenshot), it) }
    }

    private fun readProperties(path: Path): Properties =
        Properties().apply { Files.newInputStream(path).use { load(it) } }

    private fun writePropertiesAtomically(
        destination: Path,
        properties: Properties,
    ) {
        val temporary = Files.createTempFile(destination.parent, ".result-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "ktAdvancements screenshot-test result") }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun recordFailure(
        configuration: Configuration,
        failure: Throwable,
    ) {
        try {
            Files.createDirectories(configuration.workPath)
            val result =
                if (Files.isRegularFile(configuration.resultFile)) readProperties(configuration.resultFile) else Properties()
            result.getProperty("status")?.let { result.setProperty("pluginStatus", it) }
            result.setProperty("status", "failed")
            result.setProperty("captureMode", configuration.captureMode)
            result.setProperty("captureError", failure.message ?: failure.javaClass.name)
            writePropertiesAtomically(configuration.resultFile, result)
        } catch (exception: Exception) {
            logger.warn("Could not record screenshot-test failure in {}", configuration.resultFile, exception)
        }
    }

    private fun stopOwnedProcess(owned: OwnedProcess) {
        owned.refreshDescendants()
        val handles = owned.descendants.values.toList().asReversed() + owned.process.toHandle()
        handles.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroy() } }
        val gracefulDeadline = deadlineAfter(GRACEFUL_STOP_SECONDS)
        while (handles.any(ProcessHandle::isAlive) && !hasExpired(gracefulDeadline)) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
        handles.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroyForcibly() } }
        val forcedDeadline = deadlineAfter(FORCED_STOP_SECONDS)
        while (handles.any(ProcessHandle::isAlive) && !hasExpired(forcedDeadline)) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (handles.any(ProcessHandle::isAlive)) {
            logger.warn("Some owned {} processes did not exit after termination", owned.name)
        }
    }

    private fun failureMessage(
        configuration: Configuration,
        failure: Throwable,
    ): String =
        buildString {
            appendLine("Minecraft ${configuration.version} screenshot test failed: ${failure.message}")
            listOf(configuration.serverLog, configuration.clientLog, configuration.driverLog).forEach { path ->
                appendLine("Log: $path")
                val tail = logTail(path)
                if (tail.isNotEmpty()) appendLine(tail)
            }
        }

    private fun logTail(path: Path): String =
        runCatching {
            if (!Files.isRegularFile(path)) return@runCatching ""
            val lines = ArrayDeque<String>(LOG_TAIL_LINES)
            Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { source ->
                source.forEach {
                    if (lines.size == LOG_TAIL_LINES) lines.removeFirst()
                    lines.addLast(it)
                }
            }
            lines.joinToString(System.lineSeparator())
        }.getOrDefault("")

    private fun deadlineAfter(seconds: Long) = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

    private fun hasExpired(deadline: Long) = System.nanoTime() - deadline >= 0L

    private data class Configuration(
        val version: String,
        val runtimeClass: String,
        val serverPath: Path,
        val pluginPath: Path,
        val javaPath: Path,
        val portableMcPath: Path,
        val cachePath: Path,
        val workPath: Path,
        val driverPath: Path?,
        val timeoutSeconds: Long,
        val renderDelaySeconds: Long,
        val joinTimeoutSeconds: Long,
        val stageTimeoutSeconds: Long,
    ) {
        val clientDirectory: Path = workPath.resolve("client")
        val clientScreenshots: Path = clientDirectory.resolve("screenshots")
        val minecraftClientLog: Path = clientDirectory.resolve("logs/latest.log")
        val exchangeDirectory: Path = workPath.resolve("exchange")
        val exchangeScreenshots: Path = exchangeDirectory.resolve("screenshots")
        val stageFile: Path = exchangeDirectory.resolve("stage.properties")
        val resultFile: Path = workPath.resolve("result.properties")
        val serverLog: Path = workPath.resolve("server.log")
        val clientLog: Path = workPath.resolve("client.log")
        val driverLog: Path = workPath.resolve("screenshot-driver.log")
        val captureMode: String = if (driverPath == null) "manual" else "automated"
    }

    private class TestRun(
        val configuration: Configuration,
        val deadline: Long,
    ) {
        val processes = mutableListOf<OwnedProcess>()
        var server: OwnedProcess? = null
        var client: OwnedProcess? = null
    }

    private class OwnedProcess(
        val name: String,
        val process: Process,
    ) {
        val descendants = linkedMapOf<Long, ProcessHandle>()

        fun refreshDescendants() {
            runCatching {
                process.descendants().use { children ->
                    children.forEach { descendants[it.pid()] = it }
                }
            }
        }

        fun isAlive(): Boolean {
            refreshDescendants()
            return process.isAlive || descendants.values.any(ProcessHandle::isAlive)
        }
    }

    private data class Stage(
        val name: String,
        val completed: Int,
    ) {
        val progress = "$completed/10"
        val screenshot = "$name.png"
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val SCREENSHOT_WIDTH = 1280
        const val SCREENSHOT_HEIGHT = 720
        const val INITIAL_RENDER_DELAY_SECONDS = 10L
        const val POLL_MILLIS = 250L
        const val SOCKET_TIMEOUT_MILLIS = 250
        const val GRACEFUL_STOP_SECONDS = 10L
        const val FORCED_STOP_SECONDS = 5L
        const val LOG_TAIL_LINES = 40
        const val NANOS_PER_SECOND = 1_000_000_000L
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val STAGES =
            listOf(
                Stage("zero", 0),
                Stage("partial", 3),
                Stage("complete", 10),
                Stage("revoked", 9),
            )
        val CLIENT_OPTIONS =
            """
            guiScale:2
            fullscreen:false
            onboardAccessibility:false
            skipMultiplayerWarning:true
            joinedFirstServer:true
            tutorialStep:none
            forceUnicodeFont:false
            pauseOnLostFocus:false
            lang:en_us
            hideBundleTutorial:true
            """.trimIndent() + "\n"
    }
}
