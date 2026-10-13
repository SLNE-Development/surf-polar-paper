package dev.slne.surf.polarpaper.api

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jetbrains.annotations.UnmodifiableView
import java.util.*

private val api = requiredService<SurfPolarPaperApi>()

interface SurfPolarPaperApi {
    val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>

    suspend fun listWorlds(
        gameModeIdentifier: String,
        showLoadedOnly: Boolean = false
    ): List<WorldInfo>

    suspend fun createWorld(
        worldUuid: UUID,
        gameModeIdentifier: String,
        worldBytes: ByteArray
    ): SurfWorld

    suspend fun loadWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld

    suspend fun saveWorld(world: SurfWorld)

    /**
     * Saves every loaded world and keeps them loaded. Failures do not stop the remaining worlds from being saved,
     * they are thrown together afterwards.
     */
    suspend fun saveAllWorlds()

    suspend fun unloadWorld(
        world: SurfWorld,
        saveChanges: Boolean = true
    )

    suspend fun unloadAllWorlds(
        saveChanges: Boolean = true,
        beforeHook: (world: SurfWorld) -> Unit = {},
        afterHook: (world: SurfWorld) -> Unit = {}
    )

    suspend fun deleteWorld(world: SurfWorld)

    fun findWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld?

    fun <W> getPlatformWorld(world: SurfWorld): W?

    companion object : SurfPolarPaperApi by api {
        val INSTANCE get() = api
    }
}