package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import java.util.*

fun CommandAPICommand.deleteWorldCommand() = subcommand("delete") {
    withPermission(WORLD_PERMISSION)
    
    stringArgument("gameModeIdentifier")
    stringArgument("worldUuid")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: String by arguments
        val uuid = UUID.fromString(worldUuid)

        val world = SurfPolarPaperApi.findWorld(uuid, gameModeIdentifier)
            ?: throw CommandAPI.failWithString("World with UUID '$worldUuid' and game mode '$gameModeIdentifier' not found.")

        SurfPolarPaperApi.deleteWorld(world)

        player.sendText {
            success("World ")
            append(world)
            success(" deleted successfully.")
        }
    }
}