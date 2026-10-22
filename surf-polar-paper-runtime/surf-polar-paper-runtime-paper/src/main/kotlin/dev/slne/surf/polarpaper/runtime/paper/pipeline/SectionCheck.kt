package dev.slne.surf.polarpaper.runtime.paper.pipeline

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.world.UnhandledWorldDataException
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.access.LiveChunkRegistry
import dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess
import live.minehub.polarpaper.core.world.PolarWorld
import net.kyori.adventure.key.Key

/**
 * Finds data sections that nothing accounts for. Known are the registered data types, the keys of the handlers that
 * are usable on this server, and the entity keys when the entities built-in is on.
 */
class SectionCheck(settings: PolarPaperSettings, handlers: WorldHandlers) {
    val knownKeys: Set<Key> = buildSet {
        settings.dataTypes.forEach { add(it.key) }
        handlers.handlers.filter { it.isAvailable() }.forEach { add(it.type.key) }
        if (handlers.entities != null) {
            add(BuiltInData.ENTITIES.key)
            add(BuiltInData.CHUNK_PDC.key)
        }
    }

    /** Checks the stored sections of [polarWorld]'s chunks and the world data of [world], for loads without live stages. */
    fun verifyRaw(world: SurfWorldImpl, polarWorld: PolarWorld) {
        val keys = HashSet<Key>(world.data.keys)
        for (chunk in polarWorld.chunks()) {
            SectionContainerCodec.decode(chunk.userData()).keys.mapTo(keys) { Key.key(it) }
        }
        fail(world, keys)
    }

    /**
     * Checks the sections of every live chunk, of every chunk that unloaded during the live stages (see
     * [SurfWorldAccess.unloadedSectionKeys]) and the world data of [world], after all live stages.
     */
    fun verifyLive(world: SurfWorldImpl, live: LiveChunkRegistry, access: SurfWorldAccess) {
        val keys = HashSet<Key>(world.data.keys)
        live.all().values.forEach { keys += it.keys }
        keys += access.unloadedSectionKeys()
        fail(world, keys)
    }

    private fun fail(world: SurfWorldImpl, keys: Set<Key>) {
        val unhandled = keys - knownKeys
        if (unhandled.isNotEmpty()) {
            throw UnhandledWorldDataException(world.uuid, world.gameModeIdentifier, unhandled)
        }
    }
}
