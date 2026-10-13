package dev.slne.surf.polarpaper.runtime.microservice

import com.google.auto.service.AutoService
import dev.slne.surf.database.DatabaseApi
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.microservice.api.microservice.Microservice
import dev.slne.surf.polarpaper.core.common.lock.WorldLockService
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldLockTable
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldStorageTable
import dev.slne.surf.polarpaper.runtime.microservice.lock.RedisWorldLockCache
import dev.slne.surf.polarpaper.runtime.microservice.lock.WorldLockServiceImpl
import dev.slne.surf.polarpaper.runtime.microservice.lock.WorldLockStore
import dev.slne.surf.rabbitmq.api.ServerRabbitMQApi
import dev.slne.surf.redis.RedisApi
import dev.slne.surf.redis.StandaloneRedisInstance
import java.nio.file.Path
import kotlin.io.path.Path

@AutoService(Microservice::class)
class PolarPaperMicroservice : Microservice() {
    override val dataPath: Path = Path("config")

    private val databaseApi = DatabaseApi.create(dataPath)
    private val rabbitApi = ServerRabbitMQApi.create("surf-polar-paper-storage", dataPath)

    private val redisInstance = StandaloneRedisInstance(REDIS_NAME, dataPath)
    private lateinit var redisApi: RedisApi

    override suspend fun onBootstrap(args: List<String>) {
        suspendTransaction {
            SchemaUtils.create(WorldStorageTable, WorldLockTable)
        }

        redisInstance.create()
        redisApi = RedisApi.create(REDIS_NAME).freezeAndConnect()

        rabbitApi.registerRpcService<DatabaseStorageService>(DatabaseStorageServiceImpl)
        rabbitApi.registerRpcService<WorldLockService>(
            WorldLockServiceImpl(WorldLockStore(), RedisWorldLockCache(redisApi.redisson))
        )
        rabbitApi.freezeAndConnect()
    }

    override suspend fun onDisable() {
        rabbitApi.disconnect()

        if (::redisApi.isInitialized) {
            redisApi.disconnect()
        }
        redisInstance.shutdown()

        databaseApi.shutdown()
    }

    private companion object {
        const val REDIS_NAME = "surf-polar-paper"
    }
}
