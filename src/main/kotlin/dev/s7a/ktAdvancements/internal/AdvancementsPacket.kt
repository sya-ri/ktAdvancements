package dev.s7a.ktAdvancements.internal

import dev.s7a.ktAdvancements.KtAdvancement
import io.papermc.paper.adventure.AdventureComponent
import net.minecraft.advancements.Advancement
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.AdvancementProgress
import net.minecraft.advancements.AdvancementRequirements
import net.minecraft.advancements.AdvancementRewards
import net.minecraft.advancements.AdvancementType
import net.minecraft.advancements.Criterion
import net.minecraft.advancements.DisplayInfo
import net.minecraft.advancements.critereon.ImpossibleTrigger
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.resources.ResourceLocation
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import java.util.Optional

internal data class AdvancementsPacket(
    val reset: Boolean,
    val advancements: Map<KtAdvancement, Int>,
    val removed: Set<NamespacedKey>,
) {
    companion object {
        private val EmptyRewards =
            AdvancementRewards(
                0,
                arrayListOf(),
                arrayListOf(),
                Optional.empty(),
            )

        private val ImpossibleCriterion = Criterion(ImpossibleTrigger(), ImpossibleTrigger.TriggerInstance())
    }

    fun send(player: Player) {
        (player as CraftPlayer).handle.connection.send(create())
    }

    private fun create() =
        ClientboundUpdateAdvancementsPacket(
            reset,
            advancements.keys.map { it.holder() },
            removed.map { it.location() }.toSet(),
            advancements.map { it.key.id.location() to it.key.progress(it.value) }.toMap(),
        )

    private fun NamespacedKey.location() = ResourceLocation.fromNamespaceAndPath(namespace, key)

    private fun KtAdvancement.holder() = AdvancementHolder(id.location(), convert())

    private fun KtAdvancement.convert() =
        Advancement(
            if (parent != null) Optional.of(parent.id.location()) else Optional.empty(),
            Optional.of(display()),
            EmptyRewards,
            criteria.associateWith { ImpossibleCriterion },
            AdvancementRequirements.allOf(criteria),
            false,
        )

    private fun KtAdvancement.display() =
        DisplayInfo(
            CraftItemStack.asNMSCopy(display.icon),
            AdventureComponent(display.title),
            AdventureComponent(display.description),
            if (display.background != null) Optional.of(display.background.location()) else Optional.empty(),
            display.frame.type(),
            display.showToast,
            display.announceChat,
            display.visibility.isShow(this).not(),
        ).apply {
            setLocation(display.x, display.y)
        }

    private fun KtAdvancement.Display.Frame.type() =
        when (this) {
            KtAdvancement.Display.Frame.Task -> AdvancementType.TASK
            KtAdvancement.Display.Frame.Challenge -> AdvancementType.CHALLENGE
            KtAdvancement.Display.Frame.Goal -> AdvancementType.GOAL
        }

    private fun KtAdvancement.progress(progress: Int) =
        AdvancementProgress().apply {
            update(AdvancementRequirements.allOf(criteria))
            repeat(progress.coerceAtMost(requirement)) {
                grantProgress(it.toString(36))
            }
        }
}
