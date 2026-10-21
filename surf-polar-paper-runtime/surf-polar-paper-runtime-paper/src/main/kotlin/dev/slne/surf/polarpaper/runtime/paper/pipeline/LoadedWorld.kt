package dev.slne.surf.polarpaper.runtime.paper.pipeline

import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.runtime.paper.access.LiveChunkRegistry
import dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess
import dev.slne.surf.polarpaper.runtime.paper.handler.EntitiesHandler
import kotlinx.coroutines.sync.Mutex
import live.minehub.polarpaper.core.world.PolarWorld
import org.bukkit.World

/** The platform side of one loaded surf world. There is exactly one registry and one access per world. */
class LoadedWorld(
    val bukkit: World,
    val polarWorld: PolarWorld,
    val access: SurfWorldAccess,
    val live: LiveChunkRegistry
) {
    /** Held for a whole save, because a save round shares the prepared bytes of the access. */
    val saveLock = Mutex()
}

/** The handlers a world is loaded with: the instance's custom ones, the enabled built-ins, and the entities one. */
class WorldHandlers(
    val handlers: List<ChunkDataHandler<*>>,
    val entities: EntitiesHandler?
)
