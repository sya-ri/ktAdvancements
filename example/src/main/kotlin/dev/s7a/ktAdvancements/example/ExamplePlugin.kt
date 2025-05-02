package dev.s7a.ktAdvancements.example

import dev.s7a.ktAdvancements.KtAdvancementStore
import dev.s7a.ktAdvancements.KtAdvancements
import org.bukkit.plugin.java.JavaPlugin

@Suppress("unused")
class ExamplePlugin : JavaPlugin() {
    private val ktAdvancement = KtAdvancements(KtAdvancementStore.InMemory())
}
