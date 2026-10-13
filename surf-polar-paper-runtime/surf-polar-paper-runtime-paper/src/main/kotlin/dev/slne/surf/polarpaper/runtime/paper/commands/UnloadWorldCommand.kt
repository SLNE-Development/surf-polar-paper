package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.jorel.commandapi.kotlindsl.uuidArgument
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.unloadWorldCommand() = subcommand("unload") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        unloadWorld(SurfPolarPaperApi.INSTANCE, player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun unloadWorld(
    api: SurfPolarPaperApi,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    val world = api.findWorld(worldUuid, gameModeIdentifier)
        ?: throw CommandAPI.failWithString("World with UUID '$worldUuid' and game mode '$gameModeIdentifier' is not loaded.")

    api.unloadWorld(world)

    player.sendText {
        success("World ")
        append(world)
        success(" unloaded successfully.")
    }
}
