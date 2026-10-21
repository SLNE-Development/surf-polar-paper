package dev.slne.surf.polarpaper.runtime.paper.pipeline

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.WorldMigrationException
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.access.LiveChunkRegistry
import dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess
import dev.slne.surf.polarpaper.runtime.paper.handler.BuiltInHandlers
import dev.slne.surf.polarpaper.runtime.paper.raw.LegacyUserDataImporter
import dev.slne.surf.polarpaper.runtime.paper.raw.PolarRawWorld
import dev.slne.surf.polarpaper.runtime.paper.world.SurfChunkImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import live.minehub.polarpaper.core.source.BytesPolarSource
import live.minehub.polarpaper.core.world.PolarReader
import live.minehub.polarpaper.core.world.PolarWorld
import org.bukkit.Chunk

/**
 * Loads a stored world: raw migration stages, the platform world, then the live stages, with the unhandled-section
 * check at the point the design settles (see [load]).
 */
class WorldLoadPipeline(
    private val platform: PaperPlatform,
    private val readWorld: (ByteArray) -> PolarWorld = READ_POLAR
) {
    private val log = logger()

    /**
     * Runs the whole load. The unhandled-section check runs on the raw data when no live stage is pending, otherwise
     * after all live stages. [onCreated] receives the platform side as soon as the platform world exists, so the
     * caller can clean up when a later step fails.
     */
    suspend fun load(
        world: SurfWorldImpl,
        polarBytes: ByteArray?,
        migrations: List<WorldMigration>,
        onCreated: (LoadedWorld) -> Unit
    ): LoadedWorld {
        val settings = world.api.settings
        val plugin = world.api.plugin
        val handlers = WorldHandlers(
            settings.handlers + BuiltInHandlers.resolve(settings.builtInHandlers, plugin),
            BuiltInHandlers.entities(settings.builtInHandlers, plugin)
        )
        val check = SectionCheck(settings, handlers)
        val liveMigrations = migrations.filter { it.hasLiveStage }

        val polarWorld = withContext(Dispatchers.Default) { prepareRaw(world, polarBytes, migrations) }
        if (liveMigrations.isEmpty()) check.verifyRaw(world, polarWorld)

        val live = LiveChunkRegistry()
        val access = SurfWorldAccess(world, handlers.handlers, handlers.entities, live)
        val bukkit = platform.createWorld(polarWorld, world.name, access)
            ?: throw IllegalStateException("Polar failed to create platform world for $world")
        val loaded = LoadedWorld(bukkit, polarWorld, access, live)
        onCreated(loaded)

        if (liveMigrations.isNotEmpty()) {
            runLiveStages(world, loaded, liveMigrations)
            check.verifyLive(world, live)
        }
        return loaded
    }

    /** Reads, normalizes and migrates the Polar data and copies the world data into [SurfWorldImpl.data]. */
    private fun prepareRaw(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>): PolarWorld {
        val polarWorld = if (polarBytes == null || polarBytes.isEmpty()) {
            PolarWorld(EMPTY_WORLD_MIN_SECTION, EMPTY_WORLD_MAX_SECTION)
        } else {
            readWorld(polarBytes)
        }
        LegacyUserDataImporter.normalize(polarWorld)

        // All raw stages share one wrapper, so a later stage sees what an earlier one wrote
        val raw = PolarRawWorld(polarWorld)
        for (migration in migrations) {
            try {
                migration.migrateRaw(raw)
            } catch (exception: Exception) {
                log.atSevere().withCause(exception).log("Raw stage to version %s of %s failed", migration.toVersion, world)
                throw WorldMigrationException(world.uuid, world.gameModeIdentifier, migration.toVersion, exception)
            }
        }
        raw.flush()

        // Only a container holds sections. Any other world user data is Polar's own (schematic offset) and is dropped
        // on the next save, which writes the container.
        val worldData = polarWorld.userData()
        if (SectionContainerCodec.isContainer(worldData)) {
            SectionContainerCodec.decode(worldData).forEach { (key, bytes) -> world.data.putRaw(key, bytes) }
        }
        return polarWorld
    }

    /**
     * Force-loads every chunk of the Polar world, at most [MAX_CONCURRENT_CHUNKS] at a time, and runs the live stages
     * of each chunk in version order on its owning thread. A chunk is ticketed (so it stays loaded), loaded and
     * migrated, and the ticket is removed again, all under one permit and on the chunk's own thread.
     */
    private suspend fun runLiveStages(world: SurfWorldImpl, loaded: LoadedWorld, migrations: List<WorldMigration>) {
        val bukkit = loaded.bukkit
        val plugin = world.api.plugin
        val limit = Semaphore(MAX_CONCURRENT_CHUNKS)

        coroutineScope {
            loaded.polarWorld.chunks().map { it.x() to it.z() }.map { (x, z) ->
                async {
                    limit.withPermit {
                        platform.onChunkThread(bukkit, x, z) { platform.addTicket(bukkit, x, z, plugin) }
                        try {
                            val chunk = platform.loadChunk(bukkit, x, z)
                            platform.onChunkThread(bukkit, x, z) { migrateChunk(world, loaded, chunk, migrations) }
                        } finally {
                            withContext(NonCancellable) {
                                platform.onChunkThread(bukkit, x, z) { platform.removeTicket(bukkit, x, z, plugin) }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun migrateChunk(world: SurfWorldImpl, loaded: LoadedWorld, chunk: Chunk, migrations: List<WorldMigration>) {
        for (migration in migrations) {
            try {
                val sections = loaded.live.sections(chunk.x, chunk.z)
                    ?: error("The data of chunk ${chunk.x}, ${chunk.z} of $world could not be restored")
                migration.migrateLive(SurfChunkImpl(world, chunk, sections))
            } catch (exception: Exception) {
                log.atSevere().withCause(exception).log(
                    "Live stage to version %s of %s failed in chunk %s, %s",
                    migration.toVersion, world, chunk.x, chunk.z
                )
                throw WorldMigrationException(world.uuid, world.gameModeIdentifier, migration.toVersion, exception)
            }
        }
    }

    companion object {
        /** Polar's reader. A default and not a call, because reading needs a compression library that only the server has. */
        private val READ_POLAR: (ByteArray) -> PolarWorld = { PolarReader().read(BytesPolarSource(it)) }

        const val MAX_CONCURRENT_CHUNKS = 32

        // Overworld height, Y -64 to 319
        const val EMPTY_WORLD_MIN_SECTION: Byte = -4
        const val EMPTY_WORLD_MAX_SECTION: Byte = 19
    }
}
