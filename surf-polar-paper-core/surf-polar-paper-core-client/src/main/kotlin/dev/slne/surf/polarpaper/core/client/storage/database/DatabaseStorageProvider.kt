package dev.slne.surf.polarpaper.core.client.storage.database

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import java.util.*

/**
 * Stores worlds in the microservice's database. The RabbitMQ connection it uses belongs to the plugin instance.
 */
class DatabaseStorageProvider(
    serviceSupplier: () -> DatabaseStorageService
) : StorageProvider {
    private val service by lazy(serviceSupplier)

    override suspend fun connect() {
        // The shared RabbitMQ connection is connected by the plugin instance
    }

    override suspend fun disconnect() {
        // The shared RabbitMQ connection is disconnected by the plugin instance
    }

    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? {
        return service.loadWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier
        )
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) {
        service.writeWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier,
            bytes = bytes
        )
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) {
        service.deleteWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier
        )
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<WorldInfo> {
        return service.listWorlds(gameModeIdentifier)
    }
}
