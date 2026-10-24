package dev.slne.surf.polarpaper.api.world

import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.data.DataSections
import net.kyori.adventure.text.ComponentLike
import org.bukkit.World
import java.util.*

/**
 * A world identified by its [uuid] and [gameModeIdentifier]. Two worlds are equal when both match.
 */
interface SurfWorld : ComponentLike {
    val uuid: UUID
    val gameModeIdentifier: String
    val name: String

    /** The version the world was last migrated to. */
    val version: Int

    /** The API instance that owns this world. */
    val api: SurfPolarPaperApi

    /** @throws IllegalStateException if the world is not loaded */
    val bukkitWorld: World

    /** World-level data sections. */
    val data: DataSections

    /** The live chunk at [x], [z], or null when it is not loaded. */
    fun chunk(x: Int, z: Int): SurfChunk?

    suspend fun save()
    suspend fun unload(saveChanges: Boolean = true)
    suspend fun delete()

    fun info(): WorldInfo
}
