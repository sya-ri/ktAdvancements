package dev.s7a.ktAdvancements.gametest

import dev.s7a.ktAdvancements.KtAdvancement
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

internal enum class TestAdvancement(
    override val parent: TestAdvancement?,
    override val requirement: Int,
    override val defaultGranted: Boolean,
    override val visibility: KtAdvancement.Visibility,
    private val xOffset: Float,
    private val yOffset: Float,
    icon: Material,
    title: String,
    description: String,
) : KtAdvancement<TestAdvancement> {
    Root(
        parent = null,
        requirement = 1,
        defaultGranted = true,
        visibility = KtAdvancement.Visibility.Always,
        xOffset = 0F,
        yOffset = 0F,
        icon = Material.GRASS_BLOCK,
        title = "Game test",
        description = "Game-test root",
    ),
    Progress(
        parent = Root,
        requirement = 10,
        defaultGranted = false,
        visibility = KtAdvancement.Visibility.Always,
        xOffset = 1.5F,
        yOffset = 0F,
        icon = Material.STONE,
        title = "Progress",
        description = "Complete ten steps",
    ),
    Hidden(
        parent = Progress,
        requirement = 2,
        defaultGranted = false,
        visibility = KtAdvancement.Visibility.HaveProgress,
        xOffset = 1.5F,
        yOffset = 0F,
        icon = Material.DIAMOND,
        title = "Hidden progress",
        description = "Visible after the first step",
    ),
    ;

    @Suppress("DEPRECATION")
    override val id: NamespacedKey
        get() = NamespacedKey("ktadvancements_test", name.lowercase())

    override val display: KtAdvancement.Display =
        if (parent == null) {
            KtAdvancement.Display(
                x = xOffset,
                y = yOffset,
                icon = ItemStack(icon),
                title = title,
                description = description,
                background = NamespacedKey.minecraft("textures/gui/advancements/backgrounds/adventure.png"),
            )
        } else {
            KtAdvancement.Display(
                x = parent.display.x + xOffset,
                y = parent.display.y + yOffset,
                icon = ItemStack(icon),
                title = title,
                description = description,
            )
        }
}
