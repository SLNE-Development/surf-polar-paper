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

fun CommandAPICommand.loadWorldCommand() = subcommand("load") {
    withPermission(WORLD_PERMISSION)
    
    stringArgument("gameModeIdentifier")
    stringArgument("worldUuid")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: String by arguments
        val uuid = UUID.fromString(worldUuid)

        val world = runCatching {
            SurfPolarPaperApi.loadWorld(uuid, gameModeIdentifier)
        }.getOrElse { throw CommandAPI.failWithString("Failed to load world with UUID '$worldUuid' and game mode '$gameModeIdentifier': ${it.message}") }

        player.sendText {
            success("World ")
            append(world)
            success(" loaded successfully.")
        }
    }
}