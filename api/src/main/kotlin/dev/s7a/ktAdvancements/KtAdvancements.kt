package dev.s7a.ktAdvancements

import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

class KtAdvancements(
    private val store: KtAdvancementProgressStore,
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

    fun register(advancement: KtAdvancement) {
        advancements[advancement.id] = advancement
    }

    fun showAll(player: Player) {
        val progressedAdvancements = advancements.values.associateWith { store.getProgress(player, it) }
        val readOnlyStore =
            // Use fetched data
            object : KtAdvancementProgressStore {
                override fun getProgress(
                    player: Player,
                    advancement: KtAdvancement,
                ) = progressedAdvancements[advancement] ?: 0

                override fun setProgress(
                    player: Player,
                    advancement: KtAdvancement,
                    progress: Int,
                ) = throw NotImplementedError()
            }

        runtime.sendPacket(
            player,
            true,
            progressedAdvancements.filter {
                it.key.isShow(readOnlyStore, player)
            },
            emptySet(),
        )
    }

    fun grant(
        player: Player,
        id: NamespacedKey,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return grant(player, advancement)
    }

    fun grant(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return grant(player, advancement, step)
    }

    fun grant(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): Boolean {
        val requirement = advancement.requirement
        val lastProgress = store.getProgress(player, advancement)
        if (requirement <= lastProgress) return false
        val progress = (lastProgress + step).coerceAtMost(requirement)
        store.setProgress(player, advancement, progress)
        if (advancement.isShow(store, player)) {
            runtime.sendPacket(
                player,
                false,
                mapOf(advancement to progress),
                emptySet(),
            )
        }
        return true
    }

    fun revoke(
        player: Player,
        id: NamespacedKey,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return revoke(player, advancement)
    }

    fun revoke(
        player: Player,
        id: NamespacedKey,
        step: Int,
    ): Boolean {
        val advancement = advancements[id] ?: return false
        return revoke(player, advancement, step)
    }

    fun revoke(
        player: Player,
        advancement: KtAdvancement,
        step: Int = advancement.requirement,
    ): Boolean {
        val lastProgress = store.getProgress(player, advancement)
        if (lastProgress <= 0) return false
        val progress = (lastProgress - step).coerceAtLeast(0)
        store.setProgress(player, advancement, progress)
        if (advancement.isShow(store, player).not()) {
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
        return true
    }
}
