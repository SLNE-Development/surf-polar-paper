package dev.slne.surf.polarpaper.api

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.world.SurfWorld
import it.unimi.dsi.fastutil.objects.ObjectSet
import org.jetbrains.annotations.UnmodifiableView
import java.util.*

private val api = requiredService<SurfPolarPaperApi>()

interface SurfPolarPaperApi {
    val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>

    suspend fun listWorlds(
        gameModeIdentifier: String,
        showLoadedOnly: Boolean = false
    ): List<SurfWorld>

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