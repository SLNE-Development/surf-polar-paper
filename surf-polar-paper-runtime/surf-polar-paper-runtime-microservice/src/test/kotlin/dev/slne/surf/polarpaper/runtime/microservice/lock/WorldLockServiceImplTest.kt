package dev.slne.surf.polarpaper.runtime.microservice.lock

import dev.slne.surf.database.DatabaseApi
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.deleteAll
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.polarpaper.core.common.lock.WorldLockResult
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.polarpaper.runtime.microservice.MutableClock
import dev.slne.surf.polarpaper.runtime.microservice.connectDatabase
import dev.slne.surf.polarpaper.runtime.microservice.connectRedis
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldLockTable
import dev.slne.surf.polarpaper.runtime.microservice.postgresContainer
import dev.slne.surf.polarpaper.runtime.microservice.valkeyContainer
import dev.slne.surf.redis.libs.redisson.api.RedissonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldLockServiceImplTest {
    private val postgres = postgresContainer()
    private val valkey = valkeyContainer()

    private lateinit var databaseApi: DatabaseApi
    private lateinit var redisson: RedissonClient
    private lateinit var cache: RedisWorldLockCache
    private lateinit var clock: MutableClock
    private lateinit var service: WorldLockServiceImpl

    private val world = UUID.randomUUID()

    @BeforeAll
    fun connect() = runBlocking {
        postgres.start()
        valkey.start()

        databaseApi = connectDatabase(postgres)
        redisson = connectRedis(valkey)
        cache = RedisWorldLockCache(redisson)

        suspendTransaction {
            SchemaUtils.create(WorldLockTable)
        }
    }

    @AfterAll
    fun disconnect() {
        if (::redisson.isInitialized) redisson.shutdown()
        if (::databaseApi.isInitialized) databaseApi.shutdown()
        valkey.stop()
        postgres.stop()
    }

    @BeforeEach
    fun reset() = runBlocking {
        suspendTransaction { WorldLockTable.deleteAll() }
        redisson.keys.flushall()

        clock = MutableClock()
        service = WorldLockServiceImpl(WorldLockStore(), cache, clock)
    }

    private fun locked(owner: String) = WorldLockResult(acquired = false, owner = owner)
    private fun acquired(owner: String) = WorldLockResult(acquired = true, owner = owner)

    @Test
    fun `the first server gets the lock and others are told who holds it`() = runTest {
        assertEquals(acquired("lobby-1"), service.tryLock(world, "survival", "lobby-1"))
        assertEquals(locked("lobby-1"), service.tryLock(world, "survival", "lobby-2"))
    }

    @Test
    fun `the holder can lock its world again`() = runTest {
        service.tryLock(world, "survival", "lobby-1")

        assertEquals(acquired("lobby-1"), service.tryLock(world, "survival", "lobby-1"))
    }

    @Test
    fun `worlds are locked per game mode`() = runTest {
        service.tryLock(world, "survival", "lobby-1")

        assertEquals(acquired("lobby-2"), service.tryLock(world, "creative", "lobby-2"))
    }

    @Test
    fun `an expired lease can be taken over`() = runTest {
        val shortCache = WorldLockServiceImpl(WorldLockStore(), cache, clock, cacheTtl = 50.milliseconds)
        shortCache.tryLock(world, "survival", "crashed")

        clock.advance(Duration.ofSeconds(61))
        withContext(Dispatchers.Default) { delay(200) } // let the Valkey entry expire in real time

        assertEquals(acquired("lobby-2"), shortCache.tryLock(world, "survival", "lobby-2"))
    }

    @Test
    fun `renewing keeps a lease alive past its original expiry`() = runTest {
        service.tryLock(world, "survival", "lobby-1")

        clock.advance(Duration.ofSeconds(40))
        assertEquals(listOf(WorldCacheKey(world, "survival")), service.renewLocks("lobby-1"))
        clock.advance(Duration.ofSeconds(40))
        redisson.keys.flushall() // force the database path

        assertEquals(locked("lobby-1"), service.tryLock(world, "survival", "lobby-2"))
    }

    @Test
    fun `renewing does not revive an expired lease`() = runTest {
        service.tryLock(world, "survival", "lobby-1")
        clock.advance(Duration.ofSeconds(61))

        assertEquals(emptyList<WorldCacheKey>(), service.renewLocks("lobby-1"))
    }

    @Test
    fun `only the holder can unlock`() = runTest {
        service.tryLock(world, "survival", "lobby-1")

        service.unlock(world, "survival", "lobby-2")
        assertEquals(locked("lobby-1"), service.tryLock(world, "survival", "lobby-2"))

        service.unlock(world, "survival", "lobby-1")
        assertEquals(acquired("lobby-2"), service.tryLock(world, "survival", "lobby-2"))
    }

    @Test
    fun `unlockAll releases every world of a server`() = runTest {
        val other = UUID.randomUUID()
        service.tryLock(world, "survival", "lobby-1")
        service.tryLock(other, "creative", "lobby-1")

        service.unlockAll("lobby-1")

        assertEquals(acquired("lobby-2"), service.tryLock(world, "survival", "lobby-2"))
        assertEquals(acquired("lobby-2"), service.tryLock(other, "creative", "lobby-2"))
    }

    @Test
    fun `locks held by another server are answered from valkey without the database`() = runTest {
        service.tryLock(world, "survival", "lobby-1")
        suspendTransaction { WorldLockTable.deleteAll() }

        assertEquals(locked("lobby-1"), service.tryLock(world, "survival", "lobby-2"))
    }

    @Test
    fun `concurrent requests from several microservices grant a world only once`() = runBlocking {
        // Each "instance" has its own service object, they only share the database and Valkey
        val instances = List(4) { WorldLockServiceImpl(WorldLockStore(), RedisWorldLockCache(redisson), clock) }
        val contested = List(10) { UUID.randomUUID() }

        contested.forEach { worldUuid ->
            val results = withContext(Dispatchers.IO) {
                instances.mapIndexed { index, instance ->
                    async { instance.tryLock(worldUuid, "survival", "server-$index") }
                }.awaitAll()
            }

            assertEquals(1, results.count { it.acquired }, "Grants for $worldUuid: $results")
            val holder = results.single { it.acquired }.owner
            assertTrue(results.all { it.owner == holder }, "Results for $worldUuid: $results")
        }
    }
}
