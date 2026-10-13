package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPIBukkit
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class WorldCommandTest {
    private val api = mockk<SurfPolarPaperApi>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val uuid = UUID.randomUUID()
    private val world = SurfWorld(uuid, "survival", ByteArray(0))
    private val spawn = mockk<Location>()
    private val platformWorld = mockk<World> {
        every { spawnLocation } returns spawn
    }

    /** Asserts the block fails the way CommandAPI.failWithString does, which players see as a command error. */
    private fun assertCommandFails(block: suspend () -> Unit) = runTest {
        assertThrows<WrapperCommandSyntaxException> { block() }
    }

    @Test
    fun `join loads the world when needed and teleports to its spawn`() = runTest {
        coEvery { api.loadWorld(uuid, "survival") } returns world
        every { api.getPlatformWorld<World>(world) } returns platformWorld

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
    fun `load reports a command error instead of throwing the raw exception`() {
        coEvery { api.loadWorld(uuid, "survival") } throws IllegalStateException("does not exist")

        assertCommandFails { loadWorld(api, player, uuid, "survival") }
    }

    @Test
    fun `delete also deletes worlds that are not loaded`() = runTest {
        every { api.findWorld(uuid, "survival") } returns null

        deleteWorld(api, player, uuid, "survival")

        coVerify { api.deleteWorld(SurfWorld(uuid, "survival", ByteArray(0))) }
    }

    @Test
    fun `delete uses the loaded world when there is one`() = runTest {
        every { api.findWorld(uuid, "survival") } returns world

        deleteWorld(api, player, uuid, "survival")

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
    fun `list shows all stored worlds unless only loaded worlds are requested`() = runTest {
        coEvery { api.listWorlds(any(), any()) } returns listOf(WorldInfo(uuid, "survival"))

        worldsToList(api, "survival", showLoadedOnly = null)
        worldsToList(api, "survival", showLoadedOnly = true)

        coVerify(exactly = 1) { api.listWorlds("survival", false) }
        coVerify(exactly = 1) { api.listWorlds("survival", true) }
    }

    @Test
    fun `the join button runs the join command of the world`() {
        assertEquals("/sworld join survival $uuid", joinCommandFor(WorldInfo(uuid, "survival")))
    }

    @Test
    fun `create teleports to the new world`() = runTest {
        coEvery { api.createWorld(any(), "survival", any()) } returns world
        every { api.getPlatformWorld<World>(world) } returns platformWorld

        createWorld(api, player, "survival")

        verify { player.teleportAsync(spawn) }
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
            listOf("create", "delete", "join", "list", "load", "reload", "unload"),
            command.subcommands.map { it.name }
        )
        assertEquals(WORLD_PERMISSION, command.permission.permission.orElse(null))
        command.subcommands.forEach {
            assertEquals(WORLD_PERMISSION, it.permission.permission.orElse(null), "Permission of ${it.name}")
        }
    }
}
