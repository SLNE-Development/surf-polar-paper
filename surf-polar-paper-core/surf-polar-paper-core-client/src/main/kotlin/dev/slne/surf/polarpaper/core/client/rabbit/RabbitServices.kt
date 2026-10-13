package dev.slne.surf.polarpaper.core.client.rabbit

import dev.slne.surf.polarpaper.core.common.lock.WorldLockService
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import java.nio.file.Path

/**
 * The RabbitMQ connection to the microservice, shared by database storage and world locks.
 *
 * Only instantiate it when [isRabbitMqAvailable], the RabbitMQ client comes from an optional plugin.
 */
class RabbitServices(dataPath: Path) {
    private val api = ClientRabbitMQApi.create(
        pluginName = "surf-polar-paper-storage",
        path = dataPath
    )

    val databaseStorage by lazy { api.createRpcService<DatabaseStorageService>() }
    val worldLocks by lazy { api.createRpcService<WorldLockService>() }

    suspend fun connect() {
        api.freezeAndConnect()
    }

    suspend fun disconnect() {
        api.disconnect()
    }
}
