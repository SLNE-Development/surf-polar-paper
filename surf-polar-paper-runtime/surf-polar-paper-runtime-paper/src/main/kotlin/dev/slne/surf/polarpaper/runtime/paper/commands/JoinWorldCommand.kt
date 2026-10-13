package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import org.bukkit.World
import java.util.*

fun CommandAPICommand.joinWorldCommand() = subcommand("join") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    stringArgument("worldUuid")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: String by arguments
        val uuid = UUID.fromString(worldUuid)

        val world = SurfPolarPaperApi.findWorld(uuid, gameModeIdentifier)
            ?: throw CommandAPI.failWithString("World with UUID '$worldUuid' and game mode '$gameModeIdentifier' not found.")

        val platformWorld = world.platformWorld<World>()
            ?: throw CommandAPI.failWithString("World with UUID '$worldUuid' and game mode '$gameModeIdentifier' is not loaded.")

        player.teleportAsync(platformWorld.spawnLocation)

        player.sendText {
            success("Teleported to world ")
            append(world)
            success(" successfully.")
        }
    }
}