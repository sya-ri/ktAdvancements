package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.UUID

interface KtAdvancementProgressStore {
    fun getProgress(
        player: Player,
        advancement: KtAdvancement,
    ): Int

    fun setProgress(
        player: Player,
        advancement: KtAdvancement,
        progress: Int,
    )

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
