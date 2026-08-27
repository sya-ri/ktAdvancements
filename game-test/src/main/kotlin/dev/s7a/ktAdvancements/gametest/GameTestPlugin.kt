package dev.s7a.ktAdvancements.gametest

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Properties
import java.util.logging.Level

@Suppress("unused")
class GameTestPlugin : JavaPlugin() {
    private var finished = false

    override fun onEnable() {
        if (System.getProperty(ENABLED_PROPERTY) != "true") {
            logger.warning("This plugin is only intended for automated game tests")
            return
        }
        val resultFileProperty = System.getProperty(RESULT_FILE_PROPERTY)
        if (resultFileProperty == null) {
            logger.severe("Missing system property: $RESULT_FILE_PROPERTY")
            server.shutdown()
            return
        }
        val resultFile = File(resultFileProperty)
        when (val mode = System.getProperty(MODE_PROPERTY) ?: PACKET_MODE) {
            PACKET_MODE -> server.scheduler.runTask(this, Runnable { runPacketGameTest(resultFile) })
            VISUAL_MODE -> startVisualGameTest(resultFile)
            else -> finish(resultFile, failure(IllegalArgumentException("Unknown game-test mode: $mode")))
        }
    }

    private fun runPacketGameTest(resultFile: File) {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        val result =
            try {
                // Mockito discovers its mock-maker resource via the context loader.
                thread.contextClassLoader = javaClass.classLoader
                AdvancementPacketGameTest(server.javaClass).run().apply {
                    setProperty("bukkitVersion", server.bukkitVersion)
                }
            } catch (throwable: Throwable) {
                failure(throwable)
            } finally {
                thread.contextClassLoader = previousClassLoader
            }
        finish(resultFile, result)
    }

    private fun startVisualGameTest(resultFile: File) {
        try {
            val exchangeDirectory =
                File(requireNotNull(System.getProperty(EXCHANGE_DIRECTORY_PROPERTY)) {
                    "Missing system property: $EXCHANGE_DIRECTORY_PROPERTY"
                })
            VisualGameTest(
                plugin = this,
                exchangeDirectory = exchangeDirectory,
                onSuccess = { finish(resultFile, it.apply { setProperty("bukkitVersion", server.bukkitVersion) }) },
                onFailure = { finish(resultFile, failure(it)) },
            ).start()
        } catch (throwable: Throwable) {
            finish(resultFile, failure(throwable))
        }
    }

    private fun failure(throwable: Throwable): Properties {
        logger.log(Level.SEVERE, "ktAdvancements game test failed", throwable)
        return Properties().apply {
            setProperty("status", "failed")
            setProperty("bukkitVersion", server.bukkitVersion)
            setProperty(
                "error",
                StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString(),
            )
        }
    }

    private fun finish(
        resultFile: File,
        result: Properties,
    ) {
        if (finished) return
        finished = true
        try {
            resultFile.parentFile?.mkdirs()
            resultFile.outputStream().use { result.store(it, "ktAdvancements game-test result") }
        } catch (throwable: Throwable) {
            logger.log(Level.SEVERE, "Could not write ktAdvancements game-test result", throwable)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val ENABLED_PROPERTY = "ktAdvancements.gameTest"
        const val RESULT_FILE_PROPERTY = "ktAdvancements.gameTest.resultFile"
        const val MODE_PROPERTY = "ktAdvancements.gameTest.mode"
        const val EXCHANGE_DIRECTORY_PROPERTY = "ktAdvancements.gameTest.exchangeDirectory"
        const val PACKET_MODE = "packet"
        const val VISUAL_MODE = "visual"
    }
}
