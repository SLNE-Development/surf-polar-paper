package dev.slne.surf.polarpaper.runtime.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent

/** Tells the bridge about unloading chunks, so their sections do not stay in memory. */
class ChunkUnloadListener(private val bridge: PlatformWorldBridgePaper) : Listener {
    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        bridge.chunkUnloaded(event.world, event.chunk.x, event.chunk.z)
    }
}
