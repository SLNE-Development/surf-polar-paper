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
 * The Nexo handler is a stub when Nexo is not enabled; the CraftEngine handlers are added by their own task.
 */
object BuiltInHandlers {
    // TODO(Task 10): CraftEngine has no handler yet
    fun resolve(builtIns: Set<BuiltInHandler>, plugin: Plugin): List<ChunkDataHandler<*>> {
        val handlers = mutableListOf<ChunkDataHandler<*>>()
        if (BuiltInHandler.NEXO_FURNITURE in builtIns) {
            handlers += if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
                createNexoHandler(plugin)
            } else {
                UnavailableHandler(BuiltInData.NEXO_FURNITURE)
            }
        }
        return handlers
    }

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
