package dev.s7a.ktAdvancements.example

import dev.s7a.ktAdvancements.KtAdvancement
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

enum class Advancement(
    override val parent: Advancement?,
    x: Float,
    y: Float,
    icon: Material,
    title: String,
    description: String,
    frame: KtAdvancement.Display.Frame = KtAdvancement.Display.Frame.Task,
    override val requirement: Int = 1,
    override val visibility: KtAdvancement.Visibility = KtAdvancement.Visibility.Always,
    override val defaultGranted: Boolean = false,
) : KtAdvancement<Advancement> {
    HelloWorld(null, 0F, 3F, Material.GRASS_BLOCK, "Hello world", "Join the server", defaultGranted = true),
    MineStone(HelloWorld, 1.5F, 0F, Material.STONE, "Mine stone", "Mine 10 stones", requirement = 10),
    ;

    @Suppress("DEPRECATION")
    override val id: NamespacedKey
        get() = NamespacedKey("example", name.lowercase())

    override val display: KtAdvancement.Display =
        if (parent != null) {
            KtAdvancement.Display(
                parent.display.x + x,
                parent.display.y + y,
                ItemStack(icon),
                title,
                description,
                frame = frame,
            )
        } else {
            KtAdvancement.Display(
                x,
                y,
                ItemStack(icon),
                title,
                description,
                frame = frame,
                background = NamespacedKey.minecraft("textures/gui/advancements/backgrounds/adventure.png"),
            )
        }
}
