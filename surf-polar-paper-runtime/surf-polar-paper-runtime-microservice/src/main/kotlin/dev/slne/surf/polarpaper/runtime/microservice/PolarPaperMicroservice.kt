package dev.slne.surf.polarpaper.runtime.microservice

import com.google.auto.service.AutoService
import dev.slne.surf.database.DatabaseApi
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.microservice.api.microservice.Microservice
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldStorageTable
import dev.slne.surf.rabbitmq.api.ServerRabbitMQApi
import java.nio.file.Path
import kotlin.io.path.Path

@AutoService(Microservice::class)
class PolarPaperMicroservice : Microservice() {
    override val dataPath: Path = Path("config")

    private val databaseApi = DatabaseApi.create(dataPath)
    private val rabbitApi = ServerRabbitMQApi.create("surf-polar-paper-storage", dataPath)

    override suspend fun onBootstrap(args: List<String>) {
        suspendTransaction {
            SchemaUtils.create(WorldStorageTable)
        }

        rabbitApi.registerRpcService<DatabaseStorageService>(DatabaseStorageServiceImpl)
        rabbitApi.freezeAndConnect()
    }

    override suspend fun onDisable() {
        rabbitApi.disconnect()
        databaseApi.shutdown()
    }
}