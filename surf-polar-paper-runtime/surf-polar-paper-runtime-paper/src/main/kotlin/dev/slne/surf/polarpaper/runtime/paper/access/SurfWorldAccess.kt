package dev.slne.surf.polarpaper.runtime.paper.access

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.handler.EntitiesHandler
import dev.slne.surf.polarpaper.runtime.paper.world.SurfChunkImpl
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

/**
 * Polar's hook for the user data of a chunk. The user data is a [SectionContainerCodec] container; the sections
 * are restored when the chunk is live ([populateChunkData]) and captured from it when it is saved.
 */
class SurfWorldAccess(
    private val world: SurfWorldImpl,
    private val handlers: List<ChunkDataHandler<*>>,
    private val entities: EntitiesHandler?,
    private val live: LiveChunkRegistry
) : PolarWorldAccess {
    private val log = logger()

    override fun getPlugin(): Plugin = world.api.plugin

    // Restoring needs the live chunk, so it happens in populateChunkData
    override fun loadChunkData(bukkitWorld: org.bukkit.World, chunk: ChunkAccess, userData: ByteArray?) = Unit

    override fun populateChunkData(chunk: Chunk, userData: ByteArray?) {
        val x = chunk.x
        val z = chunk.z
        val sections = MapDataSections(SectionContainerCodec.decode(userData))
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

    override fun saveChunkData(
        chunk: ChunkAccess,
        blockEntities: Map<BlockPos, BlockEntity>,
        chunkEntities: Array<Entity>,
        writer: MemorySegmentWriter
    ) {
        // Wraps the live chunk without loading or generating anything
        saveChunk(CraftChunk(chunk as LevelChunk), chunkEntities, writer)
    }

    internal fun saveChunk(chunk: Chunk, chunkEntities: Array<Entity>, writer: MemorySegmentWriter) {
        val x = chunk.x
        val z = chunk.z
        try {
            val sections = live.sections(x, z) ?: MapDataSections()
            val surfChunk = SurfChunkImpl(world, chunk, sections)
            world.api.settings.chunkSaveHooks.forEach { it(surfChunk) }

            val nonPlayers = chunkEntities.filter { it !is Player }
            val claimed: MutableSet<Entity> = Collections.newSetFromMap(IdentityHashMap())
            for (handler in handlers) {
                if (!handler.isAvailable()) continue
                capture(handler, chunk, nonPlayers, claimed::add, sections)
            }
            entities?.capture(chunk, nonPlayers.filter { it !in claimed }, sections)

            // Raw bytes: Polar stores them as the chunk's user data and hands them back to populateChunkData
            writer.write(SectionContainerCodec.encode(sections.raw()))
        } catch (e: Exception) {
            // Not swallowed: Polar fails the save instead of writing a chunk that lost its data
            log.atSevere().withCause(e).log("Failed to capture the data of chunk %s, %s of %s", x, z, world)
            throw e
        }
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
