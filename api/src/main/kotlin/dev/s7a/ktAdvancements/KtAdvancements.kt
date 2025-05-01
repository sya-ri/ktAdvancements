package dev.s7a.ktAdvancements

import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Main class for the advancement system
 *
 * This class manages the registration, display, granting, and revoking of advancements.
 * It automatically selects the appropriate runtime based on the server version.
 *
 * @param store Store for saving advancement progress
 * @param runtime Custom runtime (optional)
 */
class KtAdvancements<T : KtAdvancementStore>(
    val store: T,
    runtime: KtAdvancementRuntime? = null,
) {
    private val advancements = mutableMapOf<NamespacedKey, KtAdvancement>()

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
     * Registers an advancement
     *
     * @param advancement Advancement to register
     */
    fun register(advancement: KtAdvancement) {
        advancements[advancement.id] = advancement
    }

    /**
     * Gets an advancement by ID
     *
     * @param id Advancement ID
     * @return Advancement (null if not found)
     */
    fun get(id: NamespacedKey) = advancements[id]

    /**
     * Gets all advancements
     *
     * @return List of advancements
     */
    fun getAll() = advancements.values.toList()

    /**
     * Gets all advancements with progress for a player
     *
     * @param player Target player
     * @return Map of advancement to progress
     */
    fun getAll(player: Player): Map<KtAdvancement, Int> {
        val progress = store.getProgressAll(player)
        return advancements.values.associateWith { progress[it.id] ?: 0 }
    }

    /**
     * Shows all advancements to a player
     *
     * @param player Target player
     */
    fun showAll(player: Player) {
        sendPacket(player, true, getAll(player))
    }

    /**
     * Grants an advancement to a player by ID
     *
     * @param player Target player
     * @param id Advancement ID
     * @param step Number of steps to grant
     * @return true if the advancement was found and granted
     */
    fun grant(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ) = transaction(player) {
        grant(id, step)
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
        advancement: KtAdvancement,
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
     * Revokes an advancement from a player by ID
     *
     * @param player Target player
     * @param id Advancement ID
     * @param step Number of steps to revoke
     * @return true if the advancement was found and revoked
     */
    fun revoke(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ) = transaction(player) {
        revoke(id, step)
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
        advancement: KtAdvancement,
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
     * Sets advancement progress directly by ID
     *
     * @param player Target player
     * @param id Advancement ID
     * @param progress Progress to set
     * @return true if the advancement was found and progress was set
     */
    fun set(
        player: Player,
        id: NamespacedKey,
        progress: Int,
    ) = transaction(player) {
        set(id, progress)
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
        advancement: KtAdvancement,
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
        advancements: Map<KtAdvancement, Int>,
    ) {
        val store =
            object : KtAdvancementStore {
                override fun getProgress(
                    player: Player,
                    advancement: KtAdvancement,
                ) = advancements[advancement] ?: 0

                override fun getProgressAll(player: Player) = advancements.mapKeys { it.key.id }

                override fun setProgress(
                    player: Player,
                    advancement: KtAdvancement,
                    progress: Int,
                ) = throw NotImplementedError()

                override fun setProgressAll(
                    player: Player,
                    progress: Map<KtAdvancement, Int>,
                ) = throw NotImplementedError()
            }
        val (updated, removed) = advancements.entries.partition { it.key.isShow(store, player) }
        runtime.sendPacket(
            player,
            isReset,
            updated.associate { it.key to it.value },
            removed.map { it.key.id }.toSet(),
        )
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
        private val progress = getAll(player).toMutableMap()

        /**
         * Grants an advancement by ID
         *
         * @param id Advancement ID
         * @param step Number of steps to grant
         * @return true if the advancement was found and granted
         */
        fun grant(
            id: NamespacedKey,
            step: Int,
        ): Boolean {
            val advancement = advancements[id] ?: return false
            return grant(advancement, step)
        }

        /**
         * Grants an advancement
         *
         * @param advancement Advancement to grant
         * @param step Number of steps to grant (default: advancement requirement)
         * @return true if the advancement was granted
         */
        fun grant(
            advancement: KtAdvancement,
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
         * Revokes an advancement by ID
         *
         * @param id Advancement ID
         * @param step Number of steps to revoke
         * @return true if the advancement was found and revoked
         */
        fun revoke(
            id: NamespacedKey,
            step: Int,
        ): Boolean {
            val advancement = advancements[id] ?: return false
            return revoke(advancement, step)
        }

        /**
         * Revokes an advancement
         *
         * @param advancement Advancement to revoke
         * @param step Number of steps to revoke (default: advancement requirement)
         * @return true if the advancement was revoked
         */
        fun revoke(
            advancement: KtAdvancement,
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
         * Sets advancement progress directly by ID
         *
         * @param id Advancement ID
         * @param progress Progress to set
         * @return true if the advancement was found and progress was set
         */
        fun set(
            id: NamespacedKey,
            progress: Int,
        ): Boolean {
            val advancement = advancements[id] ?: return false
            return set(advancement, progress)
        }

        /**
         * Sets advancement progress directly
         *
         * @param advancement Advancement to set progress for
         * @param progress Progress to set
         * @return true if the progress was set
         */
        fun set(
            advancement: KtAdvancement,
            progress: Int,
        ): Boolean {
            this.progress[advancement] = progress.coerceIn(0, advancement.requirement)
            return true
        }

        /**
         * Processes all updates and sends them to the player
         */
        internal fun process() {
            sendPacket(player, false, progress)
        }
    }
}
