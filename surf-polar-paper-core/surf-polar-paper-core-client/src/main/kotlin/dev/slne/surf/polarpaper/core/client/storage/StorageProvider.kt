package dev.slne.surf.polarpaper.core.client.storage

import dev.slne.surf.polarpaper.api.world.WorldInfo
import java.util.*

interface StorageProvider {
    suspend fun connect()
    suspend fun disconnect()

    suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<WorldInfo>

    /**
     * Returns the stored bytes of the world, or `null` if it does not exist.
     */
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
