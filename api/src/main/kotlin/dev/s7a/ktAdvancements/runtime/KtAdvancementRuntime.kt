package dev.s7a.ktAdvancements.runtime

import dev.s7a.ktAdvancements.KtAdvancement
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

interface KtAdvancementRuntime {
    fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement, Int>,
        removed: Set<NamespacedKey>,
    )
}
