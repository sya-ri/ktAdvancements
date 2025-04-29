package dev.s7a.ktAdvancements.runtime

import dev.s7a.ktAdvancements.KtAdvancement
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

/**
 * Advancement system runtime interface
 *
 * This interface is responsible for sending advancement packets according to the Minecraft version.
 * You can create custom implementations to support unsupported versions.
 *
 * @see dev.s7a.ktAdvancements.KtAdvancements
 */
interface KtAdvancementRuntime {
    /**
     * Sends advancement packets
     *
     * @param player Player to send packets to
     * @param reset Whether to reset advancements
     * @param advancements Map of advancements and their progress to send
     * @param removed Set of advancement IDs to remove
     */
    fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement, Int>,
        removed: Set<NamespacedKey>,
    )
}
