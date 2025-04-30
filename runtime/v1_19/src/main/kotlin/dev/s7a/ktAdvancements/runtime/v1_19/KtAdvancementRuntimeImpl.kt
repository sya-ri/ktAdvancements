@file:Suppress("ktlint:standard:package-name")

package dev.s7a.ktAdvancements.runtime.v1_19

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import net.minecraft.advancements.Advancement
import net.minecraft.advancements.AdvancementProgress
import net.minecraft.advancements.AdvancementRewards
import net.minecraft.advancements.Criterion
import net.minecraft.advancements.DisplayInfo
import net.minecraft.advancements.FrameType
import net.minecraft.advancements.critereon.ImpossibleTrigger
import net.minecraft.commands.CommandFunction
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.resources.ResourceLocation
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.v1_19_R1.entity.CraftPlayer
import org.bukkit.craftbukkit.v1_19_R1.inventory.CraftItemStack
import org.bukkit.craftbukkit.v1_19_R1.util.CraftChatMessage
import org.bukkit.entity.Player

class KtAdvancementRuntimeImpl : KtAdvancementRuntime {
    override fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement, Int>,
        removed: Set<NamespacedKey>,
    ) {
        (player as CraftPlayer).handle.connection.send(
            ClientboundUpdateAdvancementsPacket(
                reset,
                advancements.keys.map { it.convert() },
                removed.map { it.location() }.toSet(),
                advancements.map { it.key.id.location() to it.key.progress(it.value) }.toMap(),
            ),
        )
    }

    companion object {
        private val EmptyRewards =
            AdvancementRewards(
                0,
                arrayOf(),
                arrayOf(),
                CommandFunction.CacheableFunction.NONE,
            )

        private val ImpossibleCriterion = Criterion(ImpossibleTrigger.TriggerInstance())
    }

    private fun NamespacedKey.location() = ResourceLocation(namespace, key)

    private fun KtAdvancement.convert() =
        Advancement(
            id.location(),
            parent?.dummy(),
            display(),
            EmptyRewards,
            criteria.associateWith { ImpossibleCriterion },
            allOf(criteria),
        )

    private fun KtAdvancement.dummy() =
        Advancement(
            id.location(),
            null,
            null,
            EmptyRewards,
            emptyMap(),
            arrayOf(),
        )

    private fun KtAdvancement.display() =
        DisplayInfo(
            CraftItemStack.asNMSCopy(display.icon),
            CraftChatMessage.fromStringOrNull(display.title),
            CraftChatMessage.fromStringOrNull(display.description),
            display.background?.location(),
            display.frame.type(),
            display.showToast,
            false,
            false,
        ).apply {
            setLocation(display.x, display.y)
        }

    private fun KtAdvancement.Display.Frame.type() =
        when (this) {
            KtAdvancement.Display.Frame.Task -> FrameType.TASK
            KtAdvancement.Display.Frame.Challenge -> FrameType.CHALLENGE
            KtAdvancement.Display.Frame.Goal -> FrameType.GOAL
        }

    private fun KtAdvancement.progress(progress: Int) =
        AdvancementProgress().apply {
            update(criteria.associateWith { ImpossibleCriterion }, allOf(criteria))
            repeat(progress.coerceAtMost(requirement)) {
                grantProgress(it.toString(36))
            }
        }

    private fun allOf(criteria: List<String>) = criteria.map { arrayOf(it) }.toTypedArray()
}
