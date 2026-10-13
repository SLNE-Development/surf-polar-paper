package dev.slne.surf.polarpaper.runtime.microservice

import dev.slne.surf.database.DatabaseApi
import dev.slne.surf.database.TestOnlyDatabaseApi
import dev.slne.surf.database.libs.io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import dev.slne.surf.database.libs.io.r2dbc.postgresql.PostgresqlConnectionFactory
import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import dev.slne.surf.redis.libs.redisson.Redisson
import dev.slne.surf.redis.libs.redisson.api.RedissonClient
import dev.slne.surf.redis.libs.redisson.config.Config
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

fun postgresContainer() = PostgreSQLContainer("postgres:17.6-alpine")

fun valkeyContainer() = GenericContainer("valkey/valkey:9.0.6-alpine").withExposedPorts(6379)

@OptIn(TestOnlyDatabaseApi::class)
fun connectDatabase(postgres: PostgreSQLContainer<*>): DatabaseApi = DatabaseApi.create(
    PostgresqlConnectionFactory(
        PostgresqlConnectionConfiguration.builder()
            .host(postgres.host)
            .port(postgres.firstMappedPort)
            .username(postgres.username)
            .password(postgres.password)
            .database(postgres.databaseName)
            .build()
    ),
    PostgreSQLDialect()
)

// A plain Redisson client: surf-redis's own config loading clashes with the second surf-api copy on the test classpath
fun connectRedis(valkey: GenericContainer<*>): RedissonClient = Redisson.create(
    Config().apply {
        useSingleServer().address = "redis://${valkey.host}:${valkey.firstMappedPort}"
    }
)

/**
 * A clock that only moves when told to, for lease expiry tests.
 */
class MutableClock(private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}
