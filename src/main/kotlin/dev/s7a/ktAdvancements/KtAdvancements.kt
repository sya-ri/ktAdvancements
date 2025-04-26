package dev.s7a.ktAdvancements

import dev.s7a.ktAdvancements.internal.AdvancementsPacket
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

class KtAdvancements(
    private val progressStore: KtAdvancementProgressStore,
) {
    private val advancements = mutableMapOf<NamespacedKey, KtAdvancement>()

    fun showAll(player: Player) {
        AdvancementsPacket(
            reset = true,
            advancements = advancements.values.associateWith { progressStore.getProgress(player, it) },
            removed = emptySet(),
        ).send(player)
    }

    fun grant(
        player: Player,
        id: NamespacedKey,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return grant(player, advancement)
    }

    fun grant(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return grant(player, advancement, step)
    }

    fun grant(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): Boolean {
        val requirement = advancement.requirement
        val lastProgress = progressStore.getProgress(player, advancement)
        if (requirement <= lastProgress) return false
        val progress = (lastProgress + step).coerceAtMost(requirement)
        progressStore.setProgress(player, advancement, progress)
        AdvancementsPacket(
            reset = false,
            advancements = mapOf(advancement to progress),
            removed = emptySet(),
        ).send(player)
        return true
    }

    fun revoke(
        player: Player,
        id: NamespacedKey,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return revoke(player, advancement)
    }

    fun revoke(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return revoke(player, advancement, step)
    }

    fun revoke(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): Boolean {
        val lastProgress = progressStore.getProgress(player, advancement)
        if (lastProgress <= 0) return false
        val progress = (lastProgress - step).coerceAtLeast(0)
        progressStore.setProgress(player, advancement, progress)
        AdvancementsPacket(
            reset = false,
            advancements = mapOf(advancement to progress),
            removed = emptySet(),
        ).send(player)
        return true
    }
}
