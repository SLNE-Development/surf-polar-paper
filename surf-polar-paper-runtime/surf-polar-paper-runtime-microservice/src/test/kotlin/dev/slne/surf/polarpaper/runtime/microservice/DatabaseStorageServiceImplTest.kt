package dev.slne.surf.polarpaper.runtime.microservice

import dev.slne.surf.database.DatabaseApi
import dev.slne.surf.database.TestOnlyDatabaseApi
import dev.slne.surf.database.libs.io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import dev.slne.surf.database.libs.io.r2dbc.postgresql.PostgresqlConnectionFactory
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.deleteAll
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.runtime.microservice.database.tables.WorldStorageTable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.*
import kotlin.random.Random

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(TestOnlyDatabaseApi::class)
class DatabaseStorageServiceImplTest {
    // Started manually so the container is shared by all tests of the class
    private val postgres = PostgreSQLContainer("postgres:17-alpine")

    private lateinit var databaseApi: DatabaseApi

    private val service = DatabaseStorageServiceImpl

    @BeforeAll
    fun connect() = runTest {
        postgres.start()

        val connectionFactory = PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(postgres.host)
                .port(postgres.firstMappedPort)
                .username(postgres.username)
                .password(postgres.password)
                .database(postgres.databaseName)
                .build()
        )
        databaseApi = DatabaseApi.create(connectionFactory, PostgreSQLDialect())

        suspendTransaction {
            SchemaUtils.create(WorldStorageTable)
        }
    }

    @AfterAll
    fun disconnect() {
        if (::databaseApi.isInitialized) databaseApi.shutdown()
        postgres.stop()
    }

    @BeforeEach
    fun clear() = runTest {
        suspendTransaction {
            WorldStorageTable.deleteAll()
        }
    }

    @Test
    fun `written bytes are read back`() = runTest {
        val uuid = UUID.randomUUID()

        service.writeWorldBytes(uuid, "survival", byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), service.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `loading a missing world returns null`() = runTest {
        assertNull(service.loadWorldBytes(UUID.randomUUID(), "survival"))
    }

    @Test
    fun `writing the same world again updates it in place`() = runTest {
        val uuid = UUID.randomUUID()

        service.writeWorldBytes(uuid, "survival", byteArrayOf(1))
        service.writeWorldBytes(uuid, "survival", byteArrayOf(2, 2))

        assertArrayEquals(byteArrayOf(2, 2), service.loadWorldBytes(uuid, "survival"))
        assertEquals(listOf(WorldInfo(uuid, "survival")), service.listWorlds("survival"))
    }

    @Test
    fun `the same world uuid can exist in different game modes`() = runTest {
        val uuid = UUID.randomUUID()

        service.writeWorldBytes(uuid, "survival", byteArrayOf(1))
        service.writeWorldBytes(uuid, "creative", byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), service.loadWorldBytes(uuid, "survival"))
        assertArrayEquals(byteArrayOf(2), service.loadWorldBytes(uuid, "creative"))
    }

    @Test
    fun `large worlds are stored completely`() = runTest {
        val uuid = UUID.randomUUID()
        val bytes = Random(7).nextBytes(8 * 1024 * 1024)

        service.writeWorldBytes(uuid, "survival", bytes)

        assertArrayEquals(bytes, service.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `delete only removes the world of the given game mode`() = runTest {
        val uuid = UUID.randomUUID()
        service.writeWorldBytes(uuid, "survival", byteArrayOf(1))
        service.writeWorldBytes(uuid, "creative", byteArrayOf(2))

        service.deleteWorldBytes(uuid, "survival")

        assertNull(service.loadWorldBytes(uuid, "survival"))
        assertArrayEquals(byteArrayOf(2), service.loadWorldBytes(uuid, "creative"))
    }

    @Test
    fun `listWorlds returns the worlds of one game mode`() = runTest {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        service.writeWorldBytes(first, "survival", byteArrayOf(1))
        service.writeWorldBytes(second, "survival", byteArrayOf(1))
        service.writeWorldBytes(UUID.randomUUID(), "creative", byteArrayOf(1))

        assertEquals(
            setOf(WorldInfo(first, "survival"), WorldInfo(second, "survival")),
            service.listWorlds("survival").toSet()
        )
    }
}
