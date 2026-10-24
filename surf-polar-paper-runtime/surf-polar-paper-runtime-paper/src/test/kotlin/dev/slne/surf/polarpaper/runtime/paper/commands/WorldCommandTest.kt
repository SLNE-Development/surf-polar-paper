package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPIBukkit
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.api.world.SurfWorld
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import kotlinx.coroutines.test.runTest
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class WorldCommandTest {
    private val api = mockk<SurfPolarPaperApiImpl>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val uuid = UUID.randomUUID()
    private val spawn = mockk<Location>()
    private val platformWorld = mockk<World> {
        every { spawnLocation } returns spawn
    }
    private val bridge = mockk<PlatformWorldBridge> {
        every { bukkitWorld(any()) } returns platformWorld
    }
    private val world = SurfWorldImpl(uuid, "survival", 1, api, bridge)

    private val surviving = polarPaperSettings { gameMode("survival") { version = 1 } }

    /** Asserts the block fails the way CommandAPI.failWithString does, which players see as a command error. */
    private fun assertCommandFails(block: suspend () -> Unit) = runTest {
        assertThrows<WrapperCommandSyntaxException> { block() }
    }

    @Test
    fun `join loads the world when needed and teleports to its spawn`() = runTest {
        coEvery { api.loadWorld(uuid, "survival") } returns world

        joinWorld(api, player, uuid, "survival")

        verify { player.teleportAsync(spawn) }
    }

    @Test
    fun `join fails with a command error when the world cannot be loaded`() {
        coEvery { api.loadWorld(uuid, "survival") } throws IllegalStateException("does not exist")

        assertCommandFails { joinWorld(api, player, uuid, "survival") }
        verify(exactly = 0) { player.teleportAsync(any()) }
    }

    @Test
    fun `join fails with a command error when the platform world is gone`() {
        coEvery { api.loadWorld(uuid, "survival") } returns world
        every { bridge.bukkitWorld(any()) } returns null

        assertCommandFails { joinWorld(api, player, uuid, "survival") }
    }

    @Test
    fun `join uses the instance that has the world loaded`() {
        val other = mockk<SurfPolarPaperApiImpl>(relaxed = true) {
            every { findWorld(uuid, "survival") } returns world
        }
        val owner = mockk<SurfPolarPaperApiImpl>(relaxed = true) {
            every { settings } returns surviving
            every { findWorld(uuid, "survival") } returns null
        }

        assertSame(other, apiForJoin(uuid, "survival") { listOf(owner, other) })
    }

    @Test
    fun `join falls back to the owner of the game mode`() {
        val owner = mockk<SurfPolarPaperApiImpl>(relaxed = true) {
            every { settings } returns surviving
            every { findWorld(any(), any()) } returns null
        }

        assertSame(owner, apiForJoin(uuid, "survival") { listOf(owner) })
        assertThrows<WrapperCommandSyntaxException> { apiForJoin(uuid, "unowned") { listOf(owner) } }
    }

    @Test
    fun `load reports a command error instead of throwing the raw exception`() {
        coEvery { api.loadWorld(uuid, "survival") } throws IllegalStateException("does not exist")

        assertCommandFails { loadWorld(api, player, uuid, "survival") }
    }

    @Test
    fun `delete also deletes worlds that are not loaded`() = runTest {
        every { api.findWorld(uuid, "survival") } returns null

        deleteWorld(api, player, uuid, "survival", bridge)

        // Worlds are equal by uuid and game mode
        coVerify { api.deleteWorld(world) }
    }

    @Test
    fun `delete uses the loaded world when there is one`() = runTest {
        every { api.findWorld(uuid, "survival") } returns world

        deleteWorld(api, player, uuid, "survival", bridge)

        coVerify { api.deleteWorld(world) }
    }

    @Test
    fun `unload saves and unloads a loaded world`() = runTest {
        every { api.findWorld(uuid, "survival") } returns world

        unloadWorld(api, player, uuid, "survival")

        coVerify { api.unloadWorld(world, true) }
    }

    @Test
    fun `unload fails for a world that is not loaded`() {
        every { api.findWorld(uuid, "survival") } returns null

        assertCommandFails { unloadWorld(api, player, uuid, "survival") }
        coVerify(exactly = 0) { api.unloadWorld(any(), any()) }
    }

    @Test
    fun `list asks the owner of the game mode`() = runTest {
        val owner = mockk<SurfPolarPaperApiImpl> {
            every { settings } returns surviving
            coEvery { listWorlds(any(), any()) } returns listOf(WorldInfo(uuid, "survival"))
        }

        worldsToList("survival", showLoadedOnly = null, instances = listOf(owner))
        worldsToList("survival", showLoadedOnly = true, instances = listOf(owner))

        coVerify(exactly = 1) { owner.listWorlds("survival", false) }
        coVerify(exactly = 1) { owner.listWorlds("survival", true) }
    }

    @Test
    fun `list reads the storage for a game mode nobody owns`() = runTest {
        val storage = mockk<StorageProvider> {
            coEvery { listWorlds("legacy") } returns listOf(WorldInfo(uuid, "legacy"))
        }
        val other = mockk<SurfPolarPaperApiImpl> { every { settings } returns surviving }

        val worlds = worldsToList("legacy", showLoadedOnly = null, instances = listOf(other), storage = { storage })

        assertEquals(listOf(WorldInfo(uuid, "legacy")), worlds)
    }

    @Test
    fun `list shows the loaded worlds of every instance for a game mode nobody owns`() = runTest {
        val legacy = SurfWorldImpl(uuid, "legacy", 1, api, bridge)
        val one = mockk<SurfPolarPaperApiImpl> {
            every { settings } returns surviving
            every { loadedWorlds } returns ObjectOpenHashSet<SurfWorld>(listOf(legacy, world))
        }

        val worlds = worldsToList("legacy", showLoadedOnly = true, instances = listOf(one), storage = { null })

        assertEquals(listOf(WorldInfo(uuid, "legacy")), worlds)
    }

    @Test
    fun `the join button runs the join command of the world`() {
        assertEquals("/sworld join survival $uuid", joinCommandFor(WorldInfo(uuid, "survival")))
    }

    @Test
    fun `create teleports to the new world`() = runTest {
        coEvery { api.createWorld(any(), "survival", any(), any()) } returns world

        createWorld(api, player, "survival")

        verify { player.teleportAsync(spawn) }
    }

    @Test
    fun `commands refuse game modes without an owner`() {
        val exception = assertThrows<WrapperCommandSyntaxException> { ownerOrFail("skyblock") { null } }

        assertEquals("No plugin owns game mode 'skyblock'", exception.message)
    }

    @Test
    fun `the owner of a game mode is used by the commands`() {
        assertSame(api, ownerOrFail("skyblock") { api })
    }

    @Test
    fun `every subcommand is registered and requires the world permission`() {
        // Some argument types ask CommandAPI's server internals for their brigadier type when they are built
        mockkStatic(CommandAPIBukkit::class)
        every { CommandAPIBukkit.get<Any>() } returns mockk(relaxed = true)

        val command = try {
            buildWorldCommand()
        } finally {
            unmockkStatic(CommandAPIBukkit::class)
        }

        assertEquals(
            listOf("create", "delete", "join", "list", "load", "unload"),
            command.subcommands.map { it.name }
        )
        assertEquals(WORLD_PERMISSION, command.permission.permission.orElse(null))
        command.subcommands.forEach {
            assertEquals(WORLD_PERMISSION, it.permission.permission.orElse(null), "Permission of ${it.name}")
        }
    }
}
