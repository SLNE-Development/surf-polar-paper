package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.api.world.UnhandledWorldDataException
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live stage that leaves an unregistered section in a chunk aborts the load even when that chunk unloaded again
 * during the live stages. The world has more chunks than the pipeline holds tickets for (32), so the first chunks lose
 * their ticket while the later ones are still migrated. The first chunks are the ones that get the section, and a
 * later stage asks Paper to unload them, so the live registry should no longer know them when the check runs. The
 * chunks are far apart because a chunk stays loaded as long as a neighbour is held. Storage stays byte-identical.
 */
class UnhandledSectionAfterChunkUnload : Scenario("unhandled-section-after-chunk-unload") {
    private val chunkType = DataType(Key.key("integration", "chunk-only"), String.serializer())

    private val chunkCount = 40
    private val sectionChunks = 8
    private val unloadAtVisit = 36

    private val visits = AtomicInteger()
    private val sectionAt = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val unloadResults = CopyOnWriteArrayList<Boolean>()

    private val leavesSectionBehind = object : WorldMigration(2) {
        override val hasLiveStage get() = true

        override fun migrateLive(chunk: SurfChunk) {
            val visit = visits.getAndIncrement()
            if (visit < sectionChunks) {
                chunk.data[chunkType] = "left behind"
                sectionAt += chunk.x to chunk.z
            }
            // Paper keeps a chunk without ticket for a while, so the finished chunks are unloaded by hand
            if (visit == unloadAtVisit) {
                val world = chunk.bukkitChunk.world
                sectionAt.forEach { (x, z) ->
                    world.unloadChunk(x, z, false)
                    unloadResults += !world.isChunkLoaded(x, z)
                }
            }
        }
    }

    override suspend fun run(context: ScenarioContext) {
        val writer = context.newApi { gameMode("unhandled-unload") { version = 1 } }
        val uuid = UUID.randomUUID()
        val world = writer.createWorld(uuid, "unhandled-unload")
        // A block in every chunk, because Polar drops chunks that only contain air
        // Far apart, because chunks next to a chunk that is still held stay loaded with it
        for (index in 0 until chunkCount) context.anchor(world.bukkitWorld, index * CHUNK_SPACING, 0)
        writer.saveWorld(world)
        context.closeApi(writer)
        val before = context.storedBytes("unhandled-unload", uuid)

        // The reader knows neither the section nor a stage that converts it
        val reader = context.newApi {
            gameMode("unhandled-unload") {
                version = 2
                migration(leavesSectionBehind)
            }
        }
        try {
            reader.loadWorld(uuid, "unhandled-unload")
            throw ScenarioFailure("A world with the unconverted section ${chunkType.key} was loaded")
        } catch (exception: UnhandledWorldDataException) {
            check(chunkType.key in exception.keys) { "The exception names ${exception.keys} but not ${chunkType.key}" }
        }

        check(unloadResults.size == sectionChunks && unloadResults.all { it }) {
            "The chunks holding the section did not unload during the live stages ($unloadResults), the scenario proves nothing"
        }
        check(context.bukkitWorldOf(uuid) == null) { "The aborted world $uuid is still loaded in Bukkit" }
        check(before.contentEquals(context.storedBytes("unhandled-unload", uuid))) {
            "The stored world changed although the load was aborted"
        }
    }

    private companion object {
        const val CHUNK_SPACING = 12
    }
}
