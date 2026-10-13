package dev.slne.surf.polarpaper.core.client.storage.database

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import java.nio.file.Path
import java.util.*

class DatabaseStorageProvider internal constructor(
    private val rabbitApi: ClientRabbitMQApi,
    proxySupplier: () -> DatabaseStorageService
) : StorageProvider {
    constructor(dataPath: Path) : this(
        ClientRabbitMQApi.create(
            pluginName = "surf-polar-paper-storage",
            path = dataPath
        )
    )

    private constructor(rabbitApi: ClientRabbitMQApi) : this(
        rabbitApi = rabbitApi,
        proxySupplier = { rabbitApi.createRpcService<DatabaseStorageService>() }
    )

    private val proxy by lazy(proxySupplier)

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
    ): List<WorldInfo> {
        return proxy.listWorlds(gameModeIdentifier)
    }

    companion object {
        private const val RABBITMQ_CLIENT_CLASS = "dev.slne.surf.rabbitmq.api.ClientRabbitMQApi"

        /**
         * Whether the RabbitMQ client is on the classpath. The platform provides it through a separate plugin,
         * which is optional because only this storage provider needs it.
         */
        fun isRabbitMqAvailable(
            className: String = RABBITMQ_CLIENT_CLASS,
            classLoader: ClassLoader? = DatabaseStorageProvider::class.java.classLoader
        ) = try {
            Class.forName(className, false, classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
