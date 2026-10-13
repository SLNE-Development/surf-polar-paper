package dev.slne.surf.polarpaper.core.client.storage.s3

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.fakes.FakeS3Server
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.random.Random

class S3StorageProviderTest {
    private val server = FakeS3Server(bucket = "worlds", pageSize = 2)
    private val provider = S3StorageProvider(config("worlds"))

    private fun config(bucket: String) = S3StorageConfig(
        endpoint = server.endpoint,
        bucket = bucket,
        accessKey = "access",
        secretKey = "secret"
    )

    @AfterEach
    fun tearDown() = runTest {
        provider.disconnect()
        server.close()
    }

    @Test
    fun `written bytes are read back from the game mode prefix`() = runTest {
        provider.connect()
        val uuid = UUID.randomUUID()
        val bytes = Random(1).nextBytes(100_000)

        provider.writeWorldBytes(uuid, "survival", bytes)

        assertArrayEquals(bytes, server.objects["survival/$uuid.cbor"])
        assertEquals("application/cbor", server.contentTypes["survival/$uuid.cbor"])
        assertArrayEquals(bytes, provider.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `loading a missing world returns null`() = runTest {
        provider.connect()

        assertNull(provider.loadWorldBytes(UUID.randomUUID(), "survival"))
    }

    @Test
    fun `delete removes the object`() = runTest {
        provider.connect()
        val uuid = UUID.randomUUID()
        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1))

        provider.deleteWorldBytes(uuid, "survival")

        assertNull(provider.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `listWorlds follows continuation tokens and skips foreign keys`() = runTest {
        provider.connect()
        val uuids = List(5) { UUID.randomUUID() }
        uuids.forEach { provider.writeWorldBytes(it, "survival", byteArrayOf(1)) }
        provider.writeWorldBytes(UUID.randomUUID(), "creative", byteArrayOf(1))
        server.objects["survival/${UUID.randomUUID()}.zip"] = byteArrayOf(1)
        server.objects["survival/nested/${UUID.randomUUID()}.cbor"] = byteArrayOf(1)
        server.objects["survival/not-a-uuid.cbor"] = byteArrayOf(1)

        val worlds = provider.listWorlds("survival")

        assertEquals(uuids.map { WorldInfo(it, "survival") }.toSet(), worlds.toSet())
        assertEquals(5, worlds.size)
    }

    @Test
    fun `connect fails for a missing bucket`() = runTest {
        val missingBucket = S3StorageProvider(config("missing"))

        assertThrows<IllegalStateException> { missingBucket.connect() }
        assertThrows<IllegalStateException> { missingBucket.loadWorldBytes(UUID.randomUUID(), "survival") }
    }

    @Test
    fun `operations fail before connect`() = runTest {
        assertThrows<IllegalStateException> { provider.listWorlds("survival") }
    }

    @Test
    fun `the provider can reconnect after disconnecting`() = runTest {
        provider.connect()
        provider.disconnect()
        provider.connect()

        val uuid = UUID.randomUUID()
        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1))

        assertArrayEquals(byteArrayOf(1), provider.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `object keys are built from validated game mode identifiers`() {
        val uuid = UUID.fromString("6f1d1b0e-1f0a-4c3b-9a55-0d7c1c1c7a10")

        assertEquals("survival/$uuid.cbor", S3StorageProvider.objectKey(uuid, "survival"))
        assertThrows<IllegalArgumentException> { S3StorageProvider.objectKey(uuid, "") }
        assertThrows<IllegalArgumentException> { S3StorageProvider.objectKey(uuid, "../x") }
    }

    @Test
    fun `world uuids are only parsed from keys directly inside the prefix`() {
        val uuid = UUID.randomUUID()

        assertEquals(uuid, S3StorageProvider.worldUuidFromKey("survival/$uuid.cbor", "survival"))
        assertNull(S3StorageProvider.worldUuidFromKey("creative/$uuid.cbor", "survival"))
        assertNull(S3StorageProvider.worldUuidFromKey("survival/a/$uuid.cbor", "survival"))
        assertNull(S3StorageProvider.worldUuidFromKey("survival/$uuid.zip", "survival"))
        assertNull(S3StorageProvider.worldUuidFromKey("survival/nope.cbor", "survival"))
    }
}
