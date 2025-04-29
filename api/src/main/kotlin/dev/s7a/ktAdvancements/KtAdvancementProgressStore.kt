package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Interface for storing advancement progress
 */
interface KtAdvancementProgressStore {
    /**
     * Gets the progress of an advancement
     *
     * @param player Player to check
     * @param advancement Advancement to check
     * @return Current progress
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
     * @param progress Progress to set
     */
    fun setProgress(
        player: Player,
        advancement: KtAdvancement,
        progress: Int,
    )

    /**
     * In-memory store for advancement progress
     */
    class InMemory : KtAdvancementProgressStore {
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
