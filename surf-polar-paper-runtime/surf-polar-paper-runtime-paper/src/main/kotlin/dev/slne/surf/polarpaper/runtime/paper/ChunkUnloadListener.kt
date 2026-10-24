package dev.slne.surf.polarpaper.runtime.paper

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent

/**
 * Captures the data of chunks that unload while their world stays loaded. The chunks of a world that is unloading or
 * of a server that is stopping are ignored: the world was saved before, and capturing every chunk would only cost time.
 */
class ChunkUnloadListener(
    private val bridge: PlatformWorldBridgePaper,
    private val isStopping: () -> Boolean = Bukkit::isStopping
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        bridge.worldUnloading(event.world)
    }

    // Paper removes the entities of a chunk before it fires the chunk unload, so they are taken here
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        if (isStopping()) return
        bridge.entitiesUnloading(event.world, event.chunk.x, event.chunk.z, event.entities)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (isStopping()) return
        bridge.chunkUnloaded(event.world, event.chunk)
    }
}
