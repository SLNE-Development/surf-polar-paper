package dev.slne.surf.polarpaper.runtime.paper.commands

import com.github.shynixn.mccoroutine.folia.scope
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.slne.surf.api.paper.command.executors.CoroutineScopeProvider
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.client.ApiInstances
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.runtime.paper.plugin
import org.bukkit.World

const val WORLD_PERMISSION = "surf.polarpaper.runtime.command.world"

// Resolved when a command runs, instead of walking the stack for the calling plugin when it is built
internal val commandScope: CoroutineScopeProvider = { plugin.scope }

/**
 * The API instance that owns [gameModeIdentifier], for the commands that act on worlds through their owner's handlers
 * and migrations.
 */
internal fun ownerOrFail(
    gameModeIdentifier: String,
    ownerOf: (String) -> SurfPolarPaperApiImpl? = ApiInstances::ownerOf
): SurfPolarPaperApiImpl = ownerOf(gameModeIdentifier)
    ?: throw CommandAPI.failWithString("No plugin owns game mode '$gameModeIdentifier'")

/** The platform world of [world], or a command error with [message] when it is not loaded. */
internal fun platformWorldOrFail(world: SurfWorld, message: String): World = try {
    world.bukkitWorld
} catch (_: IllegalStateException) {
    throw CommandAPI.failWithString(message)
}

fun buildWorldCommand(): CommandAPICommand = CommandAPICommand("sworld").apply {
    withPermission(WORLD_PERMISSION)

    createWorldCommand()
    deleteWorldCommand()
    joinWorldCommand()
    listWorldsCommand()
    loadWorldCommand()
    unloadWorldCommand()
}

fun worldCommand() = buildWorldCommand().register()
