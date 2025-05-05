package dev.s7a.ktAdvancements

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Interface representing the definition of an advancement
 */
interface KtAdvancement<Impl : KtAdvancement<Impl>> {
    /**
     * Parent advancement (optional)
     *
     * If set to null, this advancement becomes a root advancement and a new tab will be added to the advancements screen.
     * Root advancements are typically used to create new categories or tabs in the advancements interface.
     */
    val parent: Impl?

    /**
     * Advancement ID
     */
    val id: NamespacedKey

    /**
     * Display information for the advancement
     */
    val display: Display

    /**
     * Number of steps required to complete the advancement
     */
    val requirement: Int

    /**
     * Visibility condition for the advancement
     */
    val visibility: Visibility

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
            /**
             * Task frame
             */
            Task,

            /**
             * Challenge frame
             */
            Challenge,

            /**
             * Goal frame
             */
            Goal,
        }
    }

    /**
     * Interface representing visibility condition for an advancement
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
        fun <T : KtAdvancement<T>> isShow(
            advancement: T,
            store: KtAdvancementStore<T>,
            player: Player,
        ): Boolean

        /**
         * Always visible
         */
        data object Always : Visibility {
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
                player: Player,
            ) = true
        }

        /**
         * Visible when player has any progress
         */
        data object HaveProgress : Visibility {
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
                player: Player,
            ) = 0 < store.getProgress(player, advancement)
        }

        /**
         * Visible only when advancement is granted
         */
        data object Granted : Visibility {
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
                player: Player,
            ) = advancement.isGranted(store, player)
        }

        /**
         * Visible when parent advancement is granted
         */
        data object ParentGranted : Visibility {
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
                player: Player,
            ) = advancement.parent?.isGranted(store, player) != false // null or true
        }

        /**
         * Visible when any of the specified conditions are met
         *
         * @param visibility Array of visibility conditions
         */
        class Any(
            vararg val visibility: Visibility,
        ) : Visibility {
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
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
            override fun <T : KtAdvancement<T>> isShow(
                advancement: T,
                store: KtAdvancementStore<T>,
                player: Player,
            ) = visibility.all { it.isShow(advancement, store, player) }
        }
    }
}

/**
 * Checks if the advancement is granted
 *
 * @param progress Current progress
 * @return Whether the advancement is granted
 */
fun <T : KtAdvancement<T>> T.isGranted(progress: Int) = requirement <= progress

/**
 * Checks if the advancement is granted
 *
 * @param store Store for saving advancement progress
 * @param player Player to check
 * @return Whether the advancement is granted
 */
fun <T : KtAdvancement<T>> T.isGranted(
    store: KtAdvancementStore<T>,
    player: Player,
) = isGranted(store.getProgress(player, this))

/**
 * Checks if the advancement should be shown
 *
 * @param store Store for saving advancement progress
 * @param player Player to check
 * @return Whether the advancement should be shown
 */
fun <T : KtAdvancement<T>> T.isShow(
    store: KtAdvancementStore<T>,
    player: Player,
) = visibility.isShow(this, store, player)

/**
 * Criteria for the advancement
 */
val KtAdvancement<*>.criteria: List<String>
    get() = List(requirement) { it.toString(36) }
