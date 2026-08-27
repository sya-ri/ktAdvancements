package dev.s7a.ktAdvancements.gametest

import dev.s7a.ktAdvancements.KtAdvancementStore
import dev.s7a.ktAdvancements.KtAdvancements
import dev.s7a.ktAdvancements.getProgress
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

internal class VisualGameTest(
    private val plugin: GameTestPlugin,
    exchangeDirectory: File,
    private val onSuccess: (Properties) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Listener {
    private val exchangePath = exchangeDirectory.toPath().toAbsolutePath().normalize()
    private val screenshotsPath = exchangePath.resolve(SCREENSHOTS_DIRECTORY)
    private val stagePath = exchangePath.resolve(STAGE_FILE_NAME)
    private val store = KtAdvancementStore.InMemory<TestAdvancement>()
    private val advancements = KtAdvancements(TestAdvancement.entries, store)
    private val screenshots = mutableListOf<String>()
    private val joinTimeoutNanos = timeoutNanos(JOIN_TIMEOUT_PROPERTY, DEFAULT_JOIN_TIMEOUT_SECONDS)
    private val stageTimeoutNanos = timeoutNanos(STAGE_TIMEOUT_PROPERTY, DEFAULT_STAGE_TIMEOUT_SECONDS)

    private var phase = Phase.WaitingForPlayer
    private var deadlineNanos = 0L
    private var currentStageIndex = -1
    private var player: Player? = null
    private var vanillaSyncComplete: (() -> Boolean)? = null
    private var watchdog: BukkitTask? = null

    fun start() = safely {
        checkPrimaryThread()
        prepareExchangeDirectory()
        deadlineNanos = deadlineAfter(joinTimeoutNanos)
        plugin.server.pluginManager.registerEvents(this, plugin)
        watchdog =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable(::poll),
                WATCHDOG_INITIAL_DELAY_TICKS,
                WATCHDOG_PERIOD_TICKS,
            )
        plugin.server.onlinePlayers.firstOrNull()?.let(::acceptPlayer)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        safely {
            checkPrimaryThread()
            acceptPlayer(event.player)
        }
    }

    private fun acceptPlayer(joinedPlayer: Player) {
        if (phase != Phase.WaitingForPlayer) return
        phase = Phase.Starting
        player = joinedPlayer
        vanillaSyncComplete = initialVanillaSyncCheck(joinedPlayer)
    }

    private fun poll() = safely {
        checkPrimaryThread()
        when (phase) {
            Phase.WaitingForPlayer -> {
                if (hasTimedOut()) {
                    error("No player joined within the visual game-test timeout")
                }
            }

            Phase.Starting -> pollInitialSync()
            Phase.WaitingForAck -> pollAck()
            Phase.Finished -> Unit
        }
    }

    private fun pollInitialSync() {
        val joinedPlayer = requireNotNull(player)
        check(joinedPlayer.isOnline) { "Player disconnected before the visual game test started" }
        check(!hasTimedOut()) { "Vanilla's initial advancement sync did not finish within the visual game-test timeout" }
        if (!requireNotNull(vanillaSyncComplete).invoke()) return
        vanillaSyncComplete = null
        advancements.showAll(joinedPlayer)
        checkProgress(joinedPlayer, 0)
        publishStage(0)
    }

    private fun initialVanillaSyncCheck(player: Player): () -> Boolean {
        val getHandle =
            player.javaClass.methods.singleOrNull {
                it.name == "getHandle" && it.parameterCount == 0 &&
                    !it.isBridge && it.returnType.name in SERVER_PLAYER_CLASSES
            } ?: error("Unsupported CraftPlayer shape: expected one ServerPlayer getHandle() on ${player.javaClass.name}")
        val handle = requireNotNull(getHandle.invoke(player)) { "CraftPlayer.getHandle() returned null" }
        val managerField =
            instanceFields(handle.javaClass).singleOrNull { it.type.name in PLAYER_ADVANCEMENTS_CLASSES }
                ?: error("Unsupported ServerPlayer shape: expected one advancement manager on ${handle.javaClass.name}")
        check(managerField.trySetAccessible()) { "Cannot inspect vanilla advancement manager: $managerField" }
        val manager = requireNotNull(managerField.get(handle)) { "Vanilla advancement manager was null" }
        val firstPacket =
            instanceFields(manager.javaClass).singleOrNull { it.type == Boolean::class.javaPrimitiveType }
                ?: error("Unsupported advancement manager shape: expected one boolean field on ${manager.javaClass.name}")
        check(firstPacket.trySetAccessible()) { "Cannot inspect vanilla initial advancement sync: $firstPacket" }
        // All supported versions have one instance boolean: isFirstPacket (obfuscated on older servers).
        // flushDirty clears it after the first vanilla sync; sending earlier can lose our roots to its reset.
        return { !firstPacket.getBoolean(manager) }
    }

    private fun instanceFields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .toList()

    private fun pollAck() {
        val stage = STAGES[currentStageIndex]
        val ackPath = exchangePath.resolve("ack-${stage.name}")
        if (Files.isRegularFile(ackPath)) {
            val screenshotPath = screenshotsPath.resolve(stage.screenshot)
            check(Files.isRegularFile(screenshotPath)) {
                "Acknowledgement ${ackPath.fileName} was present, but screenshot ${stage.screenshot} was missing"
            }
            check(Files.size(screenshotPath) > 0L) {
                "Screenshot ${stage.screenshot} was empty"
            }
            screenshots += stage.screenshot
            advanceToNextStage()
        } else if (hasTimedOut()) {
            error("Timed out waiting for ${ackPath.fileName}")
        }
    }

    private fun advanceToNextStage() {
        val activePlayer = requireNotNull(player)
        check(activePlayer.isOnline) { "Player disconnected during the visual game test" }
        when (currentStageIndex) {
            0 -> {
                check(advancements.grant(activePlayer, TestAdvancement.Progress, step = 3))
                checkProgress(activePlayer, 3)
                publishStage(1)
            }

            1 -> {
                check(advancements.grant(activePlayer, TestAdvancement.Progress, step = 7))
                checkProgress(activePlayer, 10)
                publishStage(2)
            }

            2 -> {
                check(advancements.revoke(activePlayer, TestAdvancement.Progress, step = 1))
                checkProgress(activePlayer, 9)
                publishStage(3)
            }

            3 -> finishSuccessfully()
            else -> error("Unexpected visual game-test stage index: $currentStageIndex")
        }
    }

    private fun publishStage(index: Int) {
        val stage = STAGES[index]
        writeStageAtomically(stage)
        currentStageIndex = index
        deadlineNanos = deadlineAfter(stageTimeoutNanos)
        phase = Phase.WaitingForAck
        requireNotNull(player).sendMessage("KTADVANCEMENTS_VISUAL_STAGE ${stage.name}")
        plugin.logger.info("Visual game-test stage ${stage.name}: ${stage.progress}")
    }

    private fun writeStageAtomically(stage: Stage) {
        val temporaryPath = Files.createTempFile(exchangePath, ".stage-", ".tmp")
        try {
            Properties().apply {
                setProperty("stage", stage.name)
                setProperty("progress", stage.progress)
            }.let { properties ->
                Files.newOutputStream(temporaryPath).use {
                    properties.store(it, "ktAdvancements visual game-test stage")
                }
            }
            Files.move(
                temporaryPath,
                stagePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    private fun prepareExchangeDirectory() {
        Files.createDirectories(screenshotsPath)
        Files.deleteIfExists(stagePath)
        STAGES.forEach { stage ->
            Files.deleteIfExists(exchangePath.resolve("ack-${stage.name}"))
        }
    }

    private fun checkProgress(
        player: Player,
        expected: Int,
    ) {
        val actual = store.getProgress(player, TestAdvancement.Progress)
        check(actual == expected) { "Expected stored progress $expected/10, got $actual/10" }
    }

    private fun finishSuccessfully() {
        check(screenshots == STAGES.map(Stage::screenshot)) {
            "Unexpected screenshot list: $screenshots"
        }
        finish()
        onSuccess(
            Properties().apply {
                setProperty("status", "passed")
                setProperty("runtime", runtimeName())
                setProperty("progress", STAGES.joinToString(",", transform = Stage::progress))
                setProperty("screenshots", screenshots.joinToString(","))
            },
        )
    }

    private fun runtimeName(): String =
        requireNotNull(
            advancements.javaClass.declaredFields
                .single { it.type.name == RUNTIME_INTERFACE_NAME }
                .apply { trySetAccessible() }
                .get(advancements),
        ).javaClass.name

    private fun safely(block: () -> Unit) {
        if (phase == Phase.Finished) return
        try {
            block()
        } catch (throwable: Throwable) {
            finish()
            onFailure(throwable)
        }
    }

    private fun finish() {
        if (phase == Phase.Finished) return
        phase = Phase.Finished
        watchdog?.cancel()
        watchdog = null
        vanillaSyncComplete = null
        HandlerList.unregisterAll(this)
    }

    private fun checkPrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Visual game-test Bukkit APIs must run on the primary server thread" }
    }

    private fun hasTimedOut() = System.nanoTime() - deadlineNanos >= 0L

    private fun deadlineAfter(timeoutNanos: Long): Long = System.nanoTime() + timeoutNanos

    private fun timeoutNanos(
        propertyName: String,
        defaultSeconds: Long,
    ): Long {
        val value = System.getProperty(propertyName) ?: return TimeUnit.SECONDS.toNanos(defaultSeconds)
        val seconds = value.toLongOrNull()
        require(seconds != null && seconds > 0L) { "$propertyName must be a positive integer: '$value'" }
        return TimeUnit.SECONDS.toNanos(seconds)
    }

    private enum class Phase {
        WaitingForPlayer,
        Starting,
        WaitingForAck,
        Finished,
    }

    private data class Stage(
        val name: String,
        val progress: String,
    ) {
        val screenshot = "$name.png"
    }

    private companion object {
        const val SCREENSHOTS_DIRECTORY = "screenshots"
        const val STAGE_FILE_NAME = "stage.properties"
        const val JOIN_TIMEOUT_PROPERTY = "ktAdvancements.gameTest.joinTimeoutSeconds"
        const val STAGE_TIMEOUT_PROPERTY = "ktAdvancements.gameTest.stageTimeoutSeconds"
        const val DEFAULT_JOIN_TIMEOUT_SECONDS = 120L
        const val DEFAULT_STAGE_TIMEOUT_SECONDS = 60L
        const val WATCHDOG_INITIAL_DELAY_TICKS = 1L
        const val WATCHDOG_PERIOD_TICKS = 1L
        const val RUNTIME_INTERFACE_NAME = "dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime"
        val SERVER_PLAYER_CLASSES = setOf("net.minecraft.server.level.ServerPlayer", "net.minecraft.server.level.EntityPlayer")
        val PLAYER_ADVANCEMENTS_CLASSES = setOf("net.minecraft.server.PlayerAdvancements", "net.minecraft.server.AdvancementDataPlayer")
        val STAGES =
            listOf(
                Stage("zero", "0/10"),
                Stage("partial", "3/10"),
                Stage("complete", "10/10"),
                Stage("revoked", "9/10"),
            )
    }
}
