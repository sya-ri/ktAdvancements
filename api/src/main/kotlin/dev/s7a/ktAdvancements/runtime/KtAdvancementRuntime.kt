package dev.s7a.ktAdvancements.runtime

import dev.s7a.ktAdvancements.KtAdvancement
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Advancement system runtime interface
 *
 * This interface is responsible for sending advancement packets according to the Minecraft version.
 * You can create custom implementations to support unsupported versions.
 *
 * @see dev.s7a.ktAdvancements.KtAdvancements
 */
interface KtAdvancementRuntime {
    /**
     * Sends advancement packets
     *
     * @param player Player to send packets to
     * @param reset Whether to reset advancements
     * @param advancements Map of advancements and their progress to send
     * @param removed Set of advancement IDs to remove
     */
    fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    )

    /**
     * Resolves official runtime implementations for the running server.
     */
    companion object {
        /**
         * Creates the official runtime matching the current Bukkit server version.
         *
         * This selects an implementation already available on the library classpath;
         * it does not download artifacts or fall back to a different server version.
         * The same selection is used when KtAdvancements receives no explicit runtime.
         *
         * @return A new runtime suitable for injection into an advancement feature.
         * @throws RuntimeException If the matching runtime is absent or cannot be constructed.
         */
        @JvmStatic
        fun resolve(): KtAdvancementRuntime {
            val version =
                Bukkit.getBukkitVersion()
                    .substringBefore('-')
                    .split('.')
                    .takeWhile { it.all(Char::isDigit) }
                    .joinToString(".")
            try {
                val name = "v" + version.replace('.', '_')
                val clazz = Class.forName("${KtAdvancementRuntime::class.java.packageName}.$name.KtAdvancementRuntimeImpl")
                val runtime = clazz.getConstructor().newInstance() as KtAdvancementRuntime
                Logger.getLogger("KtAdvancements").log(Level.INFO, "Use KtAdvancementRuntime: $name")
                return runtime
            } catch (ex: Exception) {
                throw RuntimeException("Not found runtime: $version", ex)
            }
        }
    }
}
