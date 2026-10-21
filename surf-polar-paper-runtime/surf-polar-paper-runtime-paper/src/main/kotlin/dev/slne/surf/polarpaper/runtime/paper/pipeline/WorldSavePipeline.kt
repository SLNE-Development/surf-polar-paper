package dev.slne.surf.polarpaper.runtime.paper.pipeline

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.access.LiveChunkRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Saves a loaded world: prepare every loaded chunk on its owning thread, then let Polar write. */
class WorldSavePipeline(private val platform: PaperPlatform) {
    private val log = logger()

    /**
     * Returns the Polar bytes including the chunk sections and [SurfWorldImpl.data]. A failure while preparing a chunk
     * fails the save before Polar writes anything. Saves of one world run one after another, because a save round
     * shares state with the world's [dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess].
     */
    suspend fun save(world: SurfWorldImpl, loaded: LoadedWorld): ByteArray = loaded.saveLock.withLock {
        write(world, loaded)
    }

    /**
     * [save] for the server shutdown: waits at most [lockTimeout] for the save lock, because the save that holds it may
     * be queued behind the blocked thread. After that it saves without the lock and logs a warning.
     */
    suspend fun save(world: SurfWorldImpl, loaded: LoadedWorld, lockTimeout: Duration): ByteArray {
        val deadline = TimeSource.Monotonic.markNow() + lockTimeout
        while (!loaded.saveLock.tryLock()) {
            if (deadline.hasPassedNow()) {
                log.atWarning().log("Saving %s without its save lock, another save did not finish in %s", world, lockTimeout)
                return write(world, loaded)
            }
            delay(25)
        }

        try {
            return write(world, loaded)
        } finally {
            loaded.saveLock.unlock()
        }
    }

    private suspend fun write(world: SurfWorldImpl, loaded: LoadedWorld): ByteArray {
        loaded.access.beginSaveRound()

        // Chunks generated after the load are loaded but not in the registry yet, so both sources are used
        val coordinates = LinkedHashSet<Pair<Int, Int>>()
        loaded.live.all().keys.mapTo(coordinates) { LiveChunkRegistry.chunkX(it) to LiveChunkRegistry.chunkZ(it) }
        coordinates += platform.loadedChunkCoordinates(loaded.bukkit)

        coroutineScope {
            coordinates.map { (x, z) ->
                async {
                    platform.onChunkThread(loaded.bukkit, x, z) {
                        val chunk = platform.chunkIfLoaded(loaded.bukkit, x, z)
                        // A chunk that is not live was captured when it unloaded or was never loaded, and Polar still
                        // writes it, calling the access for its data
                        if (chunk != null) loaded.access.prepareChunkSave(chunk)
                    }
                }
            }.awaitAll()
        }

        // Polar writes back the world user data its generator captured at load, so ours has to replace it
        platform.setWorldUserData(loaded.bukkit, SectionContainerCodec.encode(world.data.raw()))
        return platform.savePolar(loaded.bukkit, loaded.polarWorld, loaded.access)
    }
}
