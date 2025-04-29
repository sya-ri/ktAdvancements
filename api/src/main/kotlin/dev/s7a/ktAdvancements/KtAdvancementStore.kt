package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Interface for storing advancement progress
 *
 * This interface provides methods for storing and retrieving advancement progress.
 * Implementations can store the data in various ways, such as in memory or in a database.
 */
interface KtAdvancementStore {
    /**
     * Gets the progress of an advancement
     *
     * @param player Player to check
     * @param advancement Advancement to check
     * @return Current progress (0 if no progress has been made)
     */
    fun getProgress(
        player: Player,
        advancement: KtAdvancement,
    ): Int

    /**
     * Sets the progress of an advancement
     *
     * @param player Player to set progress for
     * @param advancement Advancement to set progress for
     * @param progress Progress to set (0 to revoke all progress)
     */
    fun setProgress(
        player: Player,
        advancement: KtAdvancement,
        progress: Int,
    )

    /**
     * In-memory store for advancement progress
     *
     * This implementation stores all progress in memory.
     * The data will be lost when the server is stopped.
     */
    class InMemory : KtAdvancementStore {
        /**
         * Map of player UUID and advancement ID to progress
         */
        private val list = mutableMapOf<Pair<UUID, NamespacedKey>, Int>()

        override fun getProgress(
            player: Player,
            advancement: KtAdvancement,
        ): Int = list[player.uniqueId to advancement.id] ?: 0

        override fun setProgress(
            player: Player,
            advancement: KtAdvancement,
            progress: Int,
        ) {
            list[player.uniqueId to advancement.id] = progress
        }
    }
}
