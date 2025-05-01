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
     * Sets progress for multiple advancements
     *
     * @param player Player to set progress for
     * @param progress Map of advancement to progress
     */
    fun setProgressAll(
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

        override fun setProgress(
            player: Player,
            advancement: KtAdvancement,
            progress: Int,
        ) {
            val map = list.getOrPut(player.uniqueId, ::mutableMapOf)
            map[advancement.id] = progress
        }

        override fun setProgressAll(
            player: Player,
            progress: Map<KtAdvancement, Int>,
        ) {
            val map = list.getOrPut(player.uniqueId, ::mutableMapOf)
            progress.forEach { (advancement, value) ->
                map[advancement.id] = value
            }
        }
    }
}
