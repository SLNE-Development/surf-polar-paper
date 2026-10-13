package dev.slne.surf.polarpaper.core.client.storage.database

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import java.util.*

class DatabaseStorageProvider(
    private val config: DatabaseStorageConfig
) : StorageProvider {
    private val rabbitApi = ClientRabbitMQApi.create(
        pluginName = "surf-polar-paper-storage",
        path = SurfPolarPaperInstance.INSTANCE.dataPath
    )

    private val proxy by lazy {
        rabbitApi.createRpcService<DatabaseStorageService>()
    }

    override suspend fun connect() {
        rabbitApi.freezeAndConnect()
    }

    override suspend fun disconnect() {
        rabbitApi.disconnect()
    }

    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? {
        return proxy.loadWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier
        )
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) {
        proxy.writeWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier,
            bytes = bytes
        )
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) {
        proxy.deleteWorldBytes(
            worldUuid = worldUuid,
            gameModeIdentifier = gameModeIdentifier
        )
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<SurfWorld> {
        return proxy.listWorlds(gameModeIdentifier)
    }
}