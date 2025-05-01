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
 * It automatically selects the appropriate runtime based on the version.
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
     * Shows all advancements to a player
     *
     * @param player Player to show advancements to
     */
    fun showAll(player: Player) {
        val progress = store.getProgressAll(player)
        val readOnlyStore =
            // Use fetched data
            object : KtAdvancementStore {
                override fun getProgress(
                    player: Player,
                    advancement: KtAdvancement,
                ) = progress[advancement.id] ?: 0

                override fun getProgressAll(player: Player) = progress

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

        runtime.sendPacket(
            player,
            true,
            advancements.values
                .associateWith { progress[it.id] ?: 0 }
                .filter { it.key.isShow(readOnlyStore, player) },
            emptySet(),
        )
    }

    /**
     * Result of granting an advancement
     */
    sealed interface GrantResult {
        /** Advancement not found */
        data object NotFound : GrantResult

        /** Advancement already granted */
        data object AlreadyGranted : GrantResult

        /**
         * Advancement granted successfully
         *
         * @param lastProgress Previous progress
         * @param progress Current progress
         * @param isGranted Whether the advancement is granted
         * @param isShow Whether the advancement is shown
         */
        data class Success(
            val lastProgress: Int,
            val progress: Int,
            val isGranted: Boolean,
            val isShow: Boolean,
        ) : GrantResult
    }

    /**
     * Grants an advancement to a player
     *
     * @param player Player to grant advancement to
     * @param id Advancement ID
     * @return Result of granting
     */
    fun grant(
        player: Player,
        id: NamespacedKey,
    ): GrantResult {
        val advancement = advancements[id] ?: return GrantResult.NotFound
        return grant(player, advancement)
    }

    /**
     * Grants an advancement to a player with step count
     *
     * @param player Player to grant advancement to
     * @param id Advancement ID
     * @param step Number of steps to grant
     * @return Result of granting
     */
    fun grant(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): GrantResult {
        val advancement = advancements[id] ?: return GrantResult.NotFound
        return grant(player, advancement, step)
    }

    /**
     * Grants an advancement to a player
     *
     * @param player Player to grant advancement to
     * @param advancement Advancement to grant
     * @param step Number of steps to grant (default: advancement requirement)
     * @return Result of granting
     */
    fun grant(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): GrantResult {
        val requirement = advancement.requirement
        val lastProgress = store.getProgress(player, advancement)
        if (requirement <= lastProgress) return GrantResult.AlreadyGranted
        val progress = (lastProgress + step).coerceAtMost(requirement)
        store.setProgress(player, advancement, progress)
        val isShow = advancement.isShow(store, player)
        if (isShow) {
            runtime.sendPacket(
                player,
                false,
                mapOf(advancement to progress),
                emptySet(),
            )
        }
        return GrantResult.Success(lastProgress, progress, progress == requirement, isShow)
    }

    /**
     * Result of revoking an advancement
     */
    sealed interface RevokeResult {
        /** Advancement not found */
        data object NotFound : RevokeResult

        /** No progress to revoke */
        data object NoProgress : RevokeResult

        /**
         * Advancement revoked successfully
         *
         * @param lastProgress Previous progress
         * @param progress Current progress
         * @param isShow Whether the advancement is shown
         */
        data class Success(
            val lastProgress: Int,
            val progress: Int,
            val isShow: Boolean,
        ) : RevokeResult
    }

    /**
     * Revokes an advancement from a player
     *
     * @param player Player to revoke advancement from
     * @param id Advancement ID
     * @return Result of revoking
     */
    fun revoke(
        player: Player,
        id: NamespacedKey,
    ): RevokeResult {
        val advancement = advancements[id] ?: return RevokeResult.NotFound
        return revoke(player, advancement)
    }

    /**
     * Revokes an advancement from a player with step count
     *
     * @param player Player to revoke advancement from
     * @param id Advancement ID
     * @param step Number of steps to revoke
     * @return Result of revoking
     */
    fun revoke(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): RevokeResult {
        val advancement = advancements[id] ?: return RevokeResult.NotFound
        return revoke(player, advancement, step)
    }

    /**
     * Revokes an advancement from a player
     *
     * @param player Player to revoke advancement from
     * @param advancement Advancement to revoke
     * @param step Number of steps to revoke (default: advancement requirement)
     * @return Result of revoking
     */
    fun revoke(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): RevokeResult {
        val lastProgress = store.getProgress(player, advancement)
        if (lastProgress <= 0) return RevokeResult.NoProgress
        val progress = (lastProgress - step).coerceAtLeast(0)
        store.setProgress(player, advancement, progress)
        val isShow = advancement.isShow(store, player)
        if (isShow) {
            runtime.sendPacket(
                player,
                false,
                mapOf(advancement to progress),
                emptySet(),
            )
        } else {
            runtime.sendPacket(
                player,
                false,
                mapOf(),
                setOf(advancement.id),
            )
        }
        return RevokeResult.Success(lastProgress, progress, isShow)
    }
}
