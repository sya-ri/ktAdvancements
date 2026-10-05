package dev.s7a.ktAdvancements.runtime.v1_21_11

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

/**
 * Provides an installed-runtime fixture without server internals.
 */
class KtAdvancementRuntimeImpl : KtAdvancementRuntime {
    /**
     * Leaves packet delivery to the real-server integration tests.
     */
    override fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    ) = Unit
}
