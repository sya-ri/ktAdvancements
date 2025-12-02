@file:Suppress("ktlint:standard:package-name")

package dev.s7a.ktAdvancements.runtime.v1_21_9

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.criteria
import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import net.minecraft.advancements.Advancement
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.AdvancementProgress
import net.minecraft.advancements.AdvancementRequirements
import net.minecraft.advancements.AdvancementRewards
import net.minecraft.advancements.AdvancementType
import net.minecraft.advancements.Criterion
import net.minecraft.advancements.DisplayInfo
import net.minecraft.advancements.critereon.ImpossibleTrigger
import net.minecraft.core.ClientAsset
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.resources.ResourceLocation
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.craftbukkit.util.CraftChatMessage
import org.bukkit.entity.Player
import java.util.Optional

class KtAdvancementRuntimeImpl : KtAdvancementRuntime {
    override fun sendPacket(
        player: Player,
        reset: Boolean,
        advancements: Map<KtAdvancement<*>, Int>,
        removed: Set<NamespacedKey>,
    ) {
        (player as CraftPlayer).handle.connection.send(
            ClientboundUpdateAdvancementsPacket(
                reset,
                advancements.keys.map { AdvancementHolder(it.id.location(), it.convert()) },
                removed.map { it.location() }.toSet(),
                advancements.map { it.key.id.location() to it.key.progress(it.value) }.toMap(),
                false,
            ),
        )
    }

    companion object {
        private val EmptyRewards =
            AdvancementRewards(
                0,
                listOf(),
                listOf(),
                Optional.empty(),
            )

        private val ImpossibleCriterion = Criterion(ImpossibleTrigger(), ImpossibleTrigger.TriggerInstance())
    }

    private fun NamespacedKey.location() = ResourceLocation.fromNamespaceAndPath(namespace, key)

    private fun NamespacedKey.clientAsset() =
        ClientAsset.ResourceTexture(NamespacedKey(namespace, key.removePrefix("textures/").removeSuffix(".png")).location())

    private fun KtAdvancement<*>.convert() =
        Advancement(
            parent?.let { Optional.of(it.id.location()) } ?: Optional.empty(),
            Optional.of(display()),
            EmptyRewards,
            criteria.associateWith { ImpossibleCriterion },
            AdvancementRequirements.allOf(criteria),
            false,
        )

    private fun KtAdvancement<*>.display() =
        DisplayInfo(
            CraftItemStack.asNMSCopy(display.icon),
            CraftChatMessage.fromStringOrNull(display.title),
            CraftChatMessage.fromStringOrNull(display.description),
            display.background?.let { Optional.of(it.clientAsset()) } ?: Optional.empty(),
            display.frame.type(),
            display.showToast,
            false,
            false,
        ).apply {
            setLocation(display.x, display.y)
        }

    private fun KtAdvancement.Display.Frame.type() =
        when (this) {
            KtAdvancement.Display.Frame.Task -> AdvancementType.TASK
            KtAdvancement.Display.Frame.Challenge -> AdvancementType.CHALLENGE
            KtAdvancement.Display.Frame.Goal -> AdvancementType.GOAL
        }

    private fun KtAdvancement<*>.progress(progress: Int) =
        AdvancementProgress().apply {
            update(AdvancementRequirements.allOf(criteria))
            repeat(progress.coerceAtMost(requirement)) {
                grantProgress(it.toString(36))
            }
        }
}
