package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

data class KtAdvancement(
    val parent: KtAdvancement?,
    val id: NamespacedKey,
    val display: Display,
    val requirement: Int = 1,
) {
    val criteria = List(requirement) { it.toString(36) }

    data class Display(
        val x: Float,
        val y: Float,
        val icon: ItemStack,
        val title: String,
        val description: String,
        val background: NamespacedKey? = null,
        val frame: Frame = Frame.Task,
        val visibility: Visibility = Visibility.Always,
        val showToast: Boolean = true,
        val announceChat: Boolean = true,
    ) {
        enum class Frame {
            Task,
            Challenge,
            Goal,
        }

        interface Visibility {
            fun isShow(advancement: KtAdvancement): Boolean

            data object Always : Visibility {
                override fun isShow(advancement: KtAdvancement) = true
            }

            data object Never : Visibility {
                override fun isShow(advancement: KtAdvancement) = false
            }
        }
    }
}
