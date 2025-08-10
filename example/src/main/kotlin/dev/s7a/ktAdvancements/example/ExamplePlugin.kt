package dev.s7a.ktAdvancements.example

import dev.s7a.ktAdvancements.KtAdvancementStore
import dev.s7a.ktAdvancements.KtAdvancements
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

@Suppress("unused")
class ExamplePlugin : JavaPlugin() {
    private val ktAdvancements = KtAdvancements(Advancement.entries, KtAdvancementStore.InMemory())

    override fun onEnable() {
        server.pluginManager.registerEvents(
            object : Listener {
                @EventHandler
                fun on(event: PlayerJoinEvent) {
                    val player = event.player
                    server.scheduler.runTaskLater(
                        this@ExamplePlugin,
                        Runnable {
                            ktAdvancements.showAll(player)
                            ktAdvancements.grant(player, Advancement.HelloWorld)
                        },
                        5L,
                    )
                }

                @EventHandler
                fun on(event: BlockBreakEvent) {
                    val player = event.player
                    val block = event.block
                    if (block.type == Material.STONE) {
                        ktAdvancements.grant(player, Advancement.MineStone, step = 1)
                    }
                }
            },
            this,
        )
    }
}
