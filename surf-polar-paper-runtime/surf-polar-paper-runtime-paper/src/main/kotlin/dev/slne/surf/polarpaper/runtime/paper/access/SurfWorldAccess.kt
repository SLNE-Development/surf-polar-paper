package dev.slne.surf.polarpaper.runtime.paper.access

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.handler.EntitiesHandler
import dev.slne.surf.polarpaper.runtime.paper.world.SurfChunkImpl
import live.minehub.polarpaper.core.generator.PolarGenerator
import live.minehub.polarpaper.core.util.MemorySegmentWriter
import live.minehub.polarpaper.core.world.PolarWorldAccess
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import org.bukkit.Chunk
import org.bukkit.craftbukkit.CraftChunk
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Polar's hook for the user data of a chunk. The user data is a [SectionContainerCodec] container.
 *
 * Loading: Polar 2.3.1 only calls [loadChunkData] (on the tick thread, right after the chunk was inserted into the
 * level) and never [populateChunkData], so everything is restored there.
 *
 * Saving is two-phase because Polar calls [saveChunkData] on the ForkJoin common pool, where the live chunk must not
 * be touched, and a throwing [saveChunkData] would leave Polar's save future uncompleted forever. [prepareChunkSave]
 * therefore captures on the chunk's owning thread first, and [saveChunkData] only writes the prepared bytes.
 *
 * Polar drops chunks that only contain air before it calls [saveChunkData], so the sections of such a chunk are lost.
 */
class SurfWorldAccess(
    private val world: SurfWorldImpl,
    private val handlers: List<ChunkDataHandler<*>>,
    private val entities: EntitiesHandler?,
    private val live: LiveChunkRegistry,
    private val bukkitChunkOf: (ChunkAccess) -> Chunk = { CraftChunk(it as LevelChunk) },
    /** The user data the Polar world currently holds for a chunk, by chunk coordinates. */
    private val existingUserData: (Int, Int) -> ByteArray? = { x, z ->
        PolarGenerator.fromWorld(world.bukkitWorld)?.polarWorld?.chunkAt(x, z)?.userData()
    }
) : PolarWorldAccess {
    private val log = logger()

    /** Container bytes captured by [prepareChunkSave], waiting for Polar to write them. */
    private val prepared = ConcurrentHashMap<Long, ByteArray>()

    /** Chunks whose stored data could not be decoded; their stored bytes are kept as they are. */
    private val undecodable = ConcurrentHashMap.newKeySet<Long>()

    override fun getPlugin(): Plugin = world.api.plugin

    override fun loadChunkData(bukkitWorld: org.bukkit.World, chunk: ChunkAccess, userData: ByteArray?) {
        load(bukkitChunkOf(chunk), userData)
    }

    /** Polar 2.3.1 never calls this; loading happens in [loadChunkData]. */
    override fun populateChunkData(chunk: Chunk, userData: ByteArray?) = Unit

    internal fun load(chunk: Chunk, userData: ByteArray?) {
        val x = chunk.x
        val z = chunk.z
        val sections = try {
            MapDataSections(SectionContainerCodec.decode(userData))
        } catch (e: Exception) {
            // The chunk is already inserted and cannot be aborted. It is marked so that prepareChunkSave skips it
            // and saveChunkData falls back to the stored bytes instead of overwriting them with an empty container.
            undecodable += LiveChunkRegistry.key(x, z)
            log.atSevere().withCause(e).log("Failed to decode the data of chunk %s, %s of %s", x, z, world)
            return
        }
        undecodable -= LiveChunkRegistry.key(x, z)
        live.put(x, z, sections)

        // A live chunk cannot be aborted anymore, so a failing handler is logged and the others still run
        for (handler in handlers) {
            if (!handler.isAvailable() || !sections.contains(handler.type.key)) continue
            guarded("restore ${handler.type.key.asString()}", x, z) { restore(handler, chunk, sections) }
        }
        entities?.let { guarded("restore entities", x, z) { it.restore(chunk, sections) } }

        val surfChunk = SurfChunkImpl(world, chunk, sections)
        for (hook in world.api.settings.chunkLoadHooks) {
            guarded("run a chunk load hook", x, z) { hook(surfChunk) }
        }
    }

    /**
     * Starts a save round: drops prepared bytes of an earlier round, so a save that was not prepared (Polar's own
     * autosave, `/polar save`) cannot write stale data. The bridge calls this, then [prepareChunkSave] for every
     * loaded chunk, then `Polar.saveWorld`.
     */
    fun beginSaveRound() {
        prepared.clear()
    }

    /**
     * Runs the save hooks and captures every handler, the unclaimed entities and the chunk's persistent data of the
     * live [chunk]. Must run on the chunk's owning region thread (the main thread on Paper), before Polar saves the
     * world. Exceptions propagate so that the save fails cleanly instead of writing incomplete data.
     */
    fun prepareChunkSave(chunk: Chunk) {
        val x = chunk.x
        val z = chunk.z
        if (LiveChunkRegistry.key(x, z) in undecodable) {
            log.atWarning().log("Keeping the stored data of chunk %s, %s of %s: it could not be decoded", x, z, world)
            return
        }
        try {
            val sections = live.sections(x, z) ?: MapDataSections()
            val surfChunk = SurfChunkImpl(world, chunk, sections)
            world.api.settings.chunkSaveHooks.forEach { it(surfChunk) }

            // Players are never stored. Polar's own entity array is ignored: it is gathered off-thread
            val nonPlayers = chunk.entities.filter { it !is Player }
            val claimed: MutableSet<Entity> = Collections.newSetFromMap(IdentityHashMap())
            val claim: (Entity) -> Unit = { claimWithPassengers(it, claimed) }
            for (handler in handlers) {
                if (!handler.isAvailable()) continue
                capture(handler, chunk, nonPlayers, claim, sections)
            }
            entities?.capture(chunk, nonPlayers.filter { it !in claimed }, sections, claimed)

            prepared[LiveChunkRegistry.key(x, z)] = SectionContainerCodec.encode(sections.raw())
        } catch (e: Exception) {
            // The round failed, so nothing prepared so far may be written by a later save
            prepared.clear()
            log.atSevere().withCause(e).log("Failed to capture the data of chunk %s, %s of %s", x, z, world)
            throw e
        }
    }

    /** Writes the bytes of [prepareChunkSave]. Polar's [chunkEntities] are ignored, see [prepareChunkSave]. */
    override fun saveChunkData(
        chunk: ChunkAccess,
        blockEntities: Map<BlockPos, BlockEntity>,
        chunkEntities: Array<Entity>,
        writer: MemorySegmentWriter
    ) {
        // Never throws: an exception here would leave Polar's save future uncompleted and hang the world save
        try {
            val bytes = try {
                bytesToWrite(bukkitChunkOf(chunk))
            } catch (e: Exception) {
                log.atSevere().withCause(e).log("Failed to write the data of chunk %s, %s of %s", chunk.locX, chunk.locZ, world)
                // Coordinates come from the chunk itself, so this works even when the bukkit chunk is unavailable
                runCatching { existingUserData(chunk.locX, chunk.locZ) }.getOrNull()
            }
            if (bytes != null) writer.write(bytes)
        } catch (e: Throwable) {
            log.atSevere().withCause(e).log("Failed to write the data of a chunk of %s", world)
        }
    }

    private fun bytesToWrite(chunk: Chunk): ByteArray? {
        prepared.remove(LiveChunkRegistry.key(chunk.x, chunk.z))?.let { return it }
        // Not prepared: write the live sections as they are, or keep what the Polar world already has
        live.sections(chunk.x, chunk.z)?.let { return SectionContainerCodec.encode(it.raw()) }
        return existingUserData(chunk.x, chunk.z)
    }

    private fun claimWithPassengers(entity: Entity, claimed: MutableSet<Entity>) {
        if (!claimed.add(entity)) return
        entity.passengers.forEach { claimWithPassengers(it, claimed) }
    }

    private fun <T : Any> restore(handler: ChunkDataHandler<T>, chunk: Chunk, sections: MapDataSections) {
        sections[handler.type]?.let { handler.restore(chunk, it) }
    }

    private fun <T : Any> capture(
        handler: ChunkDataHandler<T>,
        chunk: Chunk,
        entities: List<Entity>,
        claim: (Entity) -> Unit,
        sections: MapDataSections
    ) {
        sections[handler.type] = handler.capture(chunk, entities, claim)
    }

    private inline fun guarded(action: String, x: Int, z: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.atSevere().withCause(e).log("Failed to %s of chunk %s, %s of %s", action, x, z, world)
        }
    }
}
