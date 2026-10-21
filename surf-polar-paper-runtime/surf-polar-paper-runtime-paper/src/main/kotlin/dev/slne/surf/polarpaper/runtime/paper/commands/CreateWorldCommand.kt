package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import kotlinx.coroutines.CancellationException
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.createWorldCommand() = subcommand("create") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments

        createWorld(ownerOrFail(gameModeIdentifier), player, gameModeIdentifier)
    }
}

internal suspend fun createWorld(
    api: SurfPolarPaperApiImpl,
    player: Player,
    gameModeIdentifier: String
) {
    val world = try {
        api.createWorld(UUID.randomUUID(), gameModeIdentifier)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw CommandAPI.failWithString("Failed to create world for game mode '$gameModeIdentifier': ${exception.message ?: exception::class.simpleName}")
    }

    val platformWorld = platformWorldOrFail(world, "Failed to create world for game mode '$gameModeIdentifier'.")

    player.teleportAsync(platformWorld.spawnLocation)

    player.sendText {
        success("World ")
        append(world)
        success(" created successfully.")
    }
}
