package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.settings.BuiltInHandler
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/**
 * Resolves the built-in handlers enabled in the settings.
 *
 * [BuiltInHandler.ENTITIES] is not a [ChunkDataHandler], so [resolve] never returns it; use [entities] to get it.
 * The Nexo and CraftEngine handlers are stubs when their plugin is not enabled.
 *
 * [BuiltInHandler.CRAFT_ENGINE] enables the furniture handler only. CraftEngine custom blocks are real block states
 * that Polar already stores, so there is no blocks handler and [BuiltInData.CRAFT_ENGINE_BLOCKS] is a reserved key.
 * Polar worlds lose CraftEngine's per-chunk data: block entities (storage or ticking custom blocks) are inert after a
 * load and their data is not persisted, and constant renderers are lost unless CraftEngine rebuilds its sections.
 * CraftEngine needs `chunk-system.sync-custom-blocks-on-chunk-load: true` (global only) for that rebuild, and Polar
 * worlds should be listed in `chunk-system.blacklisted-worlds` so stale `<world dir>/craftengine/` region data cannot
 * overwrite Polar's block states. The furniture handler warns on every world load when the sync option is off.
 */
object BuiltInHandlers {
    fun resolve(builtIns: Set<BuiltInHandler>, plugin: Plugin): List<ChunkDataHandler<*>> {
        val handlers = mutableListOf<ChunkDataHandler<*>>()
        if (BuiltInHandler.NEXO_FURNITURE in builtIns) {
            handlers += if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
                createNexoHandler(plugin)
            } else {
                UnavailableHandler(BuiltInData.NEXO_FURNITURE)
            }
        }
        if (BuiltInHandler.CRAFT_ENGINE in builtIns) {
            handlers += if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
                createCraftEngineHandler(plugin)
            } else {
                UnavailableHandler(BuiltInData.CRAFT_ENGINE_FURNITURE)
            }
        }
        return handlers
    }

    // Separate function so this object does not load a CraftEngine-typed class until CraftEngine is known to be enabled
    private fun createCraftEngineHandler(plugin: Plugin): ChunkDataHandler<*> = CraftEngineFurnitureHandler(plugin)

    // Separate function so this object does not load a Nexo-typed class until Nexo is known to be enabled
    private fun createNexoHandler(plugin: Plugin): ChunkDataHandler<*> = NexoFurnitureHandler(plugin)

    /** The entities handler when [BuiltInHandler.ENTITIES] is enabled, otherwise null. */
    fun entities(builtIns: Set<BuiltInHandler>, plugin: Plugin): EntitiesHandler? =
        if (BuiltInHandler.ENTITIES in builtIns) EntitiesHandler(plugin) else null
}

/** Stands in for a built-in handler whose plugin is missing: it keeps the type registered and does nothing. */
private class UnavailableHandler<T : Any>(override val type: DataType<T>) : ChunkDataHandler<T> {
    override fun isAvailable(): Boolean = false

    override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): T? = null

    override fun restore(chunk: Chunk, value: T) = Unit
}
