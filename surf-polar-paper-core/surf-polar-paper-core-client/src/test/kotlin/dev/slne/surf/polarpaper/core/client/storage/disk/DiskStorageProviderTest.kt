package dev.slne.surf.polarpaper.core.client.storage.disk

import dev.slne.surf.polarpaper.api.world.WorldInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes

class DiskStorageProviderTest {
    @TempDir
    lateinit var dataPath: Path

    private lateinit var provider: DiskStorageProvider

    private val worldsDirectory get() = dataPath.resolve("worlds")

    @BeforeEach
    fun setUp() = runTest {
        provider = DiskStorageProvider(DiskStorageConfig("worlds"), dataPath)
        provider.connect()
    }

    @Test
    fun `connect creates the storage directory`() {
        assertTrue(worldsDirectory.exists())
    }

    @Test
    fun `written bytes are read back`() = runTest {
        val uuid = UUID.randomUUID()

        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), provider.loadWorldBytes(uuid, "survival"))
        assertTrue(worldsDirectory.resolve("survival/$uuid.cbor").exists())
    }

    @Test
    fun `writing again replaces the world and leaves no temporary file`() = runTest {
        val uuid = UUID.randomUUID()

        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1))
        provider.writeWorldBytes(uuid, "survival", byteArrayOf(2, 2))

        assertArrayEquals(byteArrayOf(2, 2), provider.loadWorldBytes(uuid, "survival"))
        assertEquals(listOf("$uuid.cbor"), worldsDirectory.resolve("survival").listDirectoryEntries().map { it.name })
    }

    @Test
    fun `loading a missing world returns null`() = runTest {
        assertNull(provider.loadWorldBytes(UUID.randomUUID(), "survival"))
    }

    @Test
    fun `worlds are separated by game mode`() = runTest {
        val uuid = UUID.randomUUID()

        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1))

        assertNull(provider.loadWorldBytes(uuid, "creative"))
    }

    @Test
    fun `delete removes the world and ignores missing worlds`() = runTest {
        val uuid = UUID.randomUUID()
        provider.writeWorldBytes(uuid, "survival", byteArrayOf(1))

        provider.deleteWorldBytes(uuid, "survival")
        provider.deleteWorldBytes(uuid, "survival")

        assertNull(provider.loadWorldBytes(uuid, "survival"))
    }

    @Test
    fun `listWorlds returns only world files of the game mode`() = runTest {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        provider.writeWorldBytes(first, "survival", byteArrayOf(1))
        provider.writeWorldBytes(second, "survival", byteArrayOf(1))
        provider.writeWorldBytes(UUID.randomUUID(), "creative", byteArrayOf(1))

        val directory = worldsDirectory.resolve("survival")
        directory.resolve("${UUID.randomUUID()}.cbor.tmp").writeBytes(byteArrayOf(1))
        directory.resolve("${UUID.randomUUID()}.zip").writeBytes(byteArrayOf(1))
        directory.resolve("not-a-uuid.cbor").writeBytes(byteArrayOf(1))
        directory.resolve("${UUID.randomUUID()}.cbor").createDirectories()

        assertEquals(
            setOf(WorldInfo(first, "survival"), WorldInfo(second, "survival")),
            provider.listWorlds("survival").toSet()
        )
    }

    @Test
    fun `listWorlds of an unknown game mode is empty`() = runTest {
        assertEquals(emptyList<WorldInfo>(), provider.listWorlds("unknown"))
    }

    @Test
    fun `game mode identifiers cannot escape the storage directory`() = runTest {
        val uuid = UUID.randomUUID()

        assertThrows<IllegalArgumentException> { provider.writeWorldBytes(uuid, "..", byteArrayOf(1)) }
        assertThrows<IllegalArgumentException> { provider.loadWorldBytes(uuid, "../outside") }
        assertThrows<IllegalArgumentException> { provider.deleteWorldBytes(uuid, "a/../..") }
        assertThrows<IllegalArgumentException> { provider.listWorlds("..") }

        assertFalse(dataPath.resolve("$uuid.cbor").exists())
    }
}
