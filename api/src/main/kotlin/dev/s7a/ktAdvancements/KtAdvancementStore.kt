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
     * Gets all progress for a player
     *
     * @param player Player to check
     * @return Map of advancement ID to progress
     */
    fun getProgressAll(player: Player): Map<NamespacedKey, Int>

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
        progress: Map<KtAdvancement, Int>,
    )

    /**
     * In-memory store for advancement progress
     *
     * This implementation stores all progress in memory.
     * The data will be lost when the server is stopped.
     */
    class InMemory : KtAdvancementStore {
        /**
         * Map of player UUID to map of advancement ID to progress
         */
        private val list = mutableMapOf<UUID, MutableMap<NamespacedKey, Int>>()

        override fun getProgress(
            player: Player,
            advancement: KtAdvancement,
        ) = list[player.uniqueId]?.get(advancement.id) ?: 0

        override fun getProgressAll(player: Player) = list[player.uniqueId].orEmpty()

        override fun updateProgress(
            player: Player,
            progress: Map<KtAdvancement, Int>,
        ) {
            list.getOrPut(player.uniqueId, ::mutableMapOf).putAll(progress.mapKeys { it.key.id })
        }
    }
}
