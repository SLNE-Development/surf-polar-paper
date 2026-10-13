package dev.slne.surf.polarpaper.core.client.storage

import dev.slne.surf.polarpaper.api.world.SurfWorld
import java.util.*

interface StorageProvider {
    suspend fun connect()
    suspend fun disconnect()

    suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<SurfWorld>

    suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray?

    suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    )

    suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    )
}