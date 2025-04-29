package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Definition of an advancement
 *
 * @param parent Parent advancement (optional)
 * @param id Advancement ID
 * @param display Display information for the advancement
 * @param requirement Number of steps required to complete the advancement (default: 1)
 * @param visibility Visibility condition for the advancement (default: Always)
 */
data class KtAdvancement(
    val parent: KtAdvancement?,
    val id: NamespacedKey,
    val display: Display,
    val requirement: Int = 1,
    val visibility: Visibility = Visibility.Always,
) {
    val criteria = List(requirement) { it.toString(36) }

    /**
     * Checks if the advancement is granted
     *
     * @param progress Current progress
     * @return Whether the advancement is granted
     */
    fun isGranted(progress: Int) = requirement <= progress

    /**
     * Checks if the advancement is granted
     *
     * @param store Store for saving advancement progress
     * @param player Player to check
     * @return Whether the advancement is granted
     */
    fun isGranted(
        store: KtAdvancementProgressStore,
        player: Player,
    ) = isGranted(store.getProgress(player, this))

    /**
     * Checks if the advancement should be shown
     *
     * @param store Store for saving advancement progress
     * @param player Player to check
     * @return Whether the advancement should be shown
     */
    fun isShow(
        store: KtAdvancementProgressStore,
        player: Player,
    ) = visibility.isShow(this, store, player)

    /**
     * Display information for an advancement
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param icon Icon item
     * @param title Title text
     * @param description Description text
     * @param background Background texture (optional)
     * @param frame Frame type (default: Task)
     * @param showToast Whether to show toast notification (default: true)
     */
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
        /**
         * Frame type for advancement display
         */
        enum class Frame {
            /** Task frame */
            Task,
            /** Challenge frame */
            Challenge,
            /** Goal frame */
            Goal,
        }
    }

    /**
     * Visibility condition for an advancement
     */
    interface Visibility {
        /**
         * Checks if the advancement should be shown
         *
         * @param advancement Advancement to check
         * @param store Store for saving advancement progress
         * @param player Player to check
         * @return Whether the advancement should be shown
         */
        fun isShow(
            advancement: KtAdvancement,
            store: KtAdvancementProgressStore,
            player: Player,
        ): Boolean

        /** Always visible */
        data object Always : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = true
        }

        /** Visible when player has any progress */
        data object HaveProgress : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = 0 < store.getProgress(player, advancement)
        }

        /** Visible only when advancement is granted */
        data object Granted : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = advancement.isGranted(store.getProgress(player, advancement))
        }

        /** Visible when parent advancement is granted */
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

        /**
         * Visible when any of the specified conditions are met
         *
         * @param visibility Array of visibility conditions
         */
        class Any(
            vararg val visibility: Visibility,
        ) : Visibility {
            override fun isShow(
                advancement: KtAdvancement,
                store: KtAdvancementProgressStore,
                player: Player,
            ) = visibility.any { it.isShow(advancement, store, player) }
        }

        /**
         * Visible when all specified conditions are met
         *
         * @param visibility Array of visibility conditions
         */
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
