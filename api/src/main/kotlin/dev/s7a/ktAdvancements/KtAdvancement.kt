package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

data class KtAdvancement(
    val parent: KtAdvancement?,
    val id: NamespacedKey,
    val display: Display,
    val requirement: Int = 1,
    val visibility: Visibility = Visibility.Always,
) {
    val criteria = List(requirement) { it.toString(36) }

    fun isGranted(progress: Int) = requirement <= progress

    fun isGranted(
        store: KtAdvancementProgressStore,
        player: Player,
    ) = isGranted(store.getProgress(player, this))

    fun isShow(
        store: KtAdvancementProgressStore,
        player: Player,
    ) = visibility.isShow(this, store, player)

    data class Display(
        val x: Float,
        val y: Float,
        val icon: ItemStack,
        val title: String,
        val description: String,
        val background: NamespacedKey? = null,
        val frame: Frame = Frame.Task,
        val showToast: Boolean = true,
    ) {
        enum class Frame {
            Task,
            Challenge,
            Goal,
        }
    }

    interface Visibility {
        fun isShow(
            advancement: KtAdvancement,
            store: KtAdvancementProgressStore,
            player: Player,
        ): Boolean

        data object Always : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = true
        }

        data object HaveProgress : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = 0 < store.getProgress(player, advancement)
        }

        data object Granted : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = advancement.isGranted(store.getProgress(player, advancement))
        }

        data object ParentGranted : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = if (advancement.parent != null) {
                advancement.parent.isGranted(store, player)
            } else {
                true
            }
        }

        class Any(
            vararg val visibility: Visibility,
        ) : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = visibility.any { it.isShow(advancement, store, player) }
        }

        class All(
            vararg val visibility: Visibility,
        ) : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = visibility.all { it.isShow(advancement, store, player) }
        }
    }
}
