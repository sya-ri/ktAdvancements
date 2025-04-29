package dev.s7a.ktAdvancements

import dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

class KtAdvancements(
    private val progressStore: KtAdvancementProgressStore,
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
                val clazz = Class.forName("${KtAdvancement::class.java.packageName}.runtime.$name.KtAdvancementRuntimeImpl")
                this.runtime = clazz.getConstructor().newInstance() as KtAdvancementRuntime
            } catch (ex: Exception) {
                throw RuntimeException("Not found runtime: $version", ex)
            }
        }
    }

    fun register(advancement: KtAdvancement) {
        advancements[advancement.id] = advancement
    }

    fun showAll(player: Player) {
        val progressedAdvancements = advancements.values.associateWith { progressStore.getProgress(player, it) }

        runtime.sendPacket(
            player,
            true,
            progressedAdvancements.filter {
                it.key.isShow(
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
                    },
                    player,
                )
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
        val lastProgress = progressStore.getProgress(player, advancement)
        if (requirement <= lastProgress) return false
        val progress = (lastProgress + step).coerceAtMost(requirement)
        progressStore.setProgress(player, advancement, progress)
        if (advancement.isShow(progressStore, player)) {
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
        val lastProgress = progressStore.getProgress(player, advancement)
        if (lastProgress <= 0) return false
        val progress = (lastProgress - step).coerceAtLeast(0)
        progressStore.setProgress(player, advancement, progress)
        if (advancement.isShow(progressStore, player).not()) {
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
