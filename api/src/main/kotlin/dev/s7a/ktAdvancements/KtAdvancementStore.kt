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
interface KtAdvancementStore<T : KtAdvancement<T>> {
    /**
     * Gets the progress of an advancement
     *
     * @param player Player to check
     * @param advancement Advancement to check
     * @return Current progress (0 if no progress has been made)
     */
    fun getProgress(
        player: Player,
        advancement: T,
    ): Int

    /**
     * Gets all progress for a player
     *
     * @param player Player to check
     * @param advancements Map of advancement ID to advancement
     * @return Map of advancement to progress
     */
    fun getProgressAll(
        player: Player,
        advancements: Map<NamespacedKey, T>,
    ): Map<T, Int>

    /**
     * Updates progress for specified advancements
     *
     * This method updates only the progress of the specified advancements,
     * leaving other advancements' progress unchanged.
     *
     * @param player Player to update progress for
     * @param progress Map of advancement to progress
     */
    fun updateProgress(
        player: Player,
        progress: Map<T, Int>,
    )

    /**
     * In-memory store for advancement progress
     *
     * This implementation stores all progress in memory.
     * The data will be lost when the server is stopped.
     */
    class InMemory<T : KtAdvancement<T>> : KtAdvancementStore<T> {
        /**
         * Map of player UUID to map of advancement ID to progress
         */
        private val list = mutableMapOf<UUID, MutableMap<T, Int>>()

        override fun getProgress(
            player: Player,
            advancement: T,
        ) = list[player.uniqueId]?.get(advancement) ?: 0

        override fun getProgressAll(
            player: Player,
            advancements: Map<NamespacedKey, T>,
        ) = list[player.uniqueId].orEmpty()

        override fun updateProgress(
            player: Player,
            progress: Map<T, Int>,
        ) {
            list.getOrPut(player.uniqueId, ::mutableMapOf).putAll(progress.mapKeys { it.key })
        }
    }
}
