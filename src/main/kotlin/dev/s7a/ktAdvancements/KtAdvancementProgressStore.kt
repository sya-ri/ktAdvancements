package dev.s7a.ktAdvancements

import org.bukkit.entity.Player

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
}
