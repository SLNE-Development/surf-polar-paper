package dev.slne.surf.polarpaper.runtime.paper.pipeline

import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.access.LiveChunkRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Saves a loaded world: prepare every live chunk on its owning thread, then let Polar write. */
class WorldSavePipeline(private val platform: PaperPlatform) {
    /**
     * Returns the Polar bytes including the chunk sections and [SurfWorldImpl.data]. A failure while preparing a chunk
     * fails the save before Polar writes anything.
     */
    suspend fun save(world: SurfWorldImpl, loaded: LoadedWorld): ByteArray {
        loaded.access.beginSaveRound()

        coroutineScope {
            loaded.live.all().keys.map { key ->
                val x = LiveChunkRegistry.chunkX(key)
                val z = LiveChunkRegistry.chunkZ(key)
                async {
                    platform.onChunkThread(loaded.bukkit, x, z) {
                        // A chunk that is gone since has nothing to capture, Polar keeps what it stored for it
                        platform.chunkIfLoaded(loaded.bukkit, x, z)?.let(loaded.access::prepareChunkSave)
                    }
                }
            }.awaitAll()
        }

        // Polar writes back the world user data its generator captured at load, so ours has to replace it
        platform.setWorldUserData(loaded.bukkit, SectionContainerCodec.encode(world.data.raw()))
        return platform.savePolar(loaded.bukkit, loaded.polarWorld, loaded.access)
    }
}
