package dev.s7a.ktAdvancements

import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Main class for the advancement system
 *
 * This class manages the registration, display, granting, and revoking of advancements.
 * It automatically selects the appropriate runtime based on the server version.
 *
 * @param advancements List of advancement
 * @param store Store for saving advancement progress
 * @param runtime Custom runtime (optional)
 */
class KtAdvancements<T : KtAdvancement<T>, S : KtAdvancementStore<T>>(
    private val advancements: List<T>,
    val store: S,
    runtime: KtAdvancementRuntime? = null,
) {
    private val runtime: KtAdvancementRuntime

    init {
        if (runtime != null) {
            this.runtime = runtime
        } else {
            val version = Bukkit.getBukkitVersion().substringBefore('-')
            try {
                val name = "v" + version.replace('.', '_')
                val clazz = Class.forName("${KtAdvancementRuntime::class.java.packageName}.$name.KtAdvancementRuntimeImpl")
                this.runtime = clazz.getConstructor().newInstance() as KtAdvancementRuntime
                Logger.getLogger("KtAdvancements").log(Level.INFO, "Use KtAdvancementRuntime: $name")
            } catch (ex: Exception) {
                throw RuntimeException("Not found runtime: $version", ex)
            }
        }
    }

    /**
     * Gets all advancements
     *
     * @return List of advancements
     */
    fun getAll() = advancements.toList()

    /**
     * Gets all advancements with progress for a player
     *
     * @param player Target player
     * @return Map of advancement to progress
     */
    fun getAll(player: Player) = store.getProgress(player, advancements)

    /**
     * Shows all advancements to a player
     *
     * @param player Target player
     */
    fun showAll(player: Player) {
        sendPacket(player, true, getAll(player))
    }

    /**
     * Grants an advancement to a player
     *
     * @param player Target player
     * @param advancement Advancement to grant
     * @param step Number of steps to grant (default: advancement requirement)
     * @return true if the advancement was granted
     */
    fun grant(
        player: Player,
        advancement: T,
        step: Int = advancement.requirement,
    ) = transaction(player) {
        grant(advancement, step)
    }

    /**
     * Grants all advancements to a player
     *
     * @param player Target player
     * @return true if any advancement was granted
     */
    fun grantAll(player: Player) =
        transaction(player) {
            grantAll()
        }

    /**
     * Revokes an advancement from a player
     *
     * @param player Target player
     * @param advancement Advancement to revoke
     * @param step Number of steps to revoke (default: advancement requirement)
     * @return true if the advancement was revoked
     */
    fun revoke(
        player: Player,
        advancement: T,
        step: Int = advancement.requirement,
    ) = transaction(player) {
        revoke(advancement, step)
    }

    /**
     * Revokes all advancements from a player
     *
     * @param player Target player
     * @return true if any advancement was revoked
     */
    fun revokeAll(player: Player) =
        transaction(player) {
            revokeAll()
        }

    /**
     * Sets advancement progress directly
     *
     * @param player Target player
     * @param advancement Advancement to set progress for
     * @param progress Progress to set
     * @return true if the progress was set
     */
    fun set(
        player: Player,
        advancement: T,
        progress: Int,
    ) = transaction(player) {
        set(advancement, progress)
    }

    /**
     * Executes a transaction for updating advancement progress
     *
     * @param player Target player
     * @param block Block to update progress
     * @return Result of the transaction
     */
    fun <R> transaction(
        player: Player,
        block: Transaction.() -> R,
    ) = Transaction(player).run {
        block().apply {
            process()
        }
    }

    /**
     * Sends advancement progress to a player
     *
     * @param player Target player
     * @param isReset Whether to reset all progress before sending
     * @param advancements Progress to send
     */
    private fun sendPacket(
        player: Player,
        isReset: Boolean,
        advancements: Map<T, Int>,
        lastAdvancements: Map<T, Int> = emptyMap(),
    ) {
        val (updated, removed) = partitionAdvancements(player, advancements)
        val (lastUpdated, lastRemoved) = partitionAdvancements(player, lastAdvancements)
        runtime.sendPacket(
            player,
            isReset,
            updated.filter { it.value != lastUpdated[it.key] },
            removed.filterNot(lastRemoved::contains).map(KtAdvancement<*>::id).toSet(),
        )
    }

    /**
     * Partitions the advancement progress map into those that should be shown and those that should be removed
     *
     * @param player Target player
     * @param progress Map of advancement progress
     * @return Pair of map of advancements to show and set of advancement IDs to remove
     */
    private fun partitionAdvancements(
        player: Player,
        progress: Map<T, Int>,
    ): Pair<Map<T, Int>, List<T>> {
        val store =
            object : KtAdvancementStore<T> {
                override fun getProgress(
                    player: Player,
                    advancements: List<T>,
                ) = progress.filterKeys(advancements::contains)

                override fun updateProgress(
                    player: Player,
                    progress: Map<T, Int>,
                ) = throw NotImplementedError()
            }
        val (updated, removed) = progress.entries.partition { it.key.isShow(store, player) }
        return updated.associate { it.key to it.value } to removed.map { it.key }
    }

    /**
     * Transaction class for updating advancement progress
     *
     * This class manages batch updates of advancement progress and
     * sends changes to the player when the update is complete.
     */
    inner class Transaction(
        private val player: Player,
    ) {
        private val lastProgress = getAll(player)
        private val progress = lastProgress.toMutableMap()

        /**
         * Grants an advancement
         *
         * @param advancement Advancement to grant
         * @param step Number of steps to grant (default: advancement requirement)
         * @return true if the advancement was granted
         */
        fun grant(
            advancement: T,
            step: Int = advancement.requirement,
        ): Boolean {
            val current = progress[advancement] ?: 0
            return set(advancement, current + step)
        }

        /**
         * Grants all advancements
         *
         * @return true if any advancement was granted
         */
        fun grantAll(): Boolean {
            if (progress.isEmpty()) return false
            progress.keys.forEach { advancement ->
                progress[advancement] = advancement.requirement
            }
            return true
        }

        /**
         * Revokes an advancement
         *
         * @param advancement Advancement to revoke
         * @param step Number of steps to revoke (default: advancement requirement)
         * @return true if the advancement was revoked
         */
        fun revoke(
            advancement: T,
            step: Int = advancement.requirement,
        ): Boolean {
            val current = progress[advancement] ?: 0
            return set(advancement, current - step)
        }

        /**
         * Revokes all advancements
         *
         * @return true if any advancement was revoked
         */
        fun revokeAll(): Boolean {
            if (progress.isEmpty()) return false
            progress.keys.forEach { advancement ->
                progress[advancement] = 0
            }
            return true
        }

        /**
         * Sets advancement progress directly
         *
         * @param advancement Advancement to set progress for
         * @param progress Progress to set
         * @return true if the progress was set
         */
        fun set(
            advancement: T,
            progress: Int,
        ): Boolean {
            this.progress[advancement] = progress.coerceIn(0, advancement.requirement)
            return true
        }

        /**
         * Processes all updates and sends them to the player
         */
        internal fun process() {
            val diff = progress.filter { it.value != lastProgress[it.key] }
            if (diff.isEmpty()) return
            store.updateProgress(player, diff)
            sendPacket(player, false, progress, lastProgress)
        }
    }
}
