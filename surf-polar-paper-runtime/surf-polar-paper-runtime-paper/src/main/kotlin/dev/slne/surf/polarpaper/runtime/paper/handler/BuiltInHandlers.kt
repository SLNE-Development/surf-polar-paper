package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.settings.BuiltInHandler
import org.bukkit.plugin.Plugin

/**
 * Resolves the built-in handlers enabled in the settings.
 *
 * [BuiltInHandler.ENTITIES] is not a [ChunkDataHandler], so [resolve] never returns it; use [entities] to get it.
 * Nexo and CraftEngine handlers are added by their own tasks.
 */
object BuiltInHandlers {
    // TODO(Tasks 9, 10): builtIns and plugin are used once the Nexo and CraftEngine handlers exist
    @Suppress("UNUSED_PARAMETER")
    fun resolve(builtIns: Set<BuiltInHandler>, plugin: Plugin): List<ChunkDataHandler<*>> {
        val handlers = mutableListOf<ChunkDataHandler<*>>()
        // NEXO_FURNITURE and CRAFT_ENGINE have no handlers yet
        return handlers
    }

    /** The entities handler when [BuiltInHandler.ENTITIES] is enabled, otherwise null. */
    fun entities(builtIns: Set<BuiltInHandler>, plugin: Plugin): EntitiesHandler? =
        if (BuiltInHandler.ENTITIES in builtIns) EntitiesHandler(plugin) else null
}
