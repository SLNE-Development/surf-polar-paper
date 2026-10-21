package dev.slne.surf.polarpaper.runtime.paper.commands

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

fun CommandAPICommand.deleteWorldCommand() = subcommand("delete") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        deleteWorld(TODO("Task 8"), player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun deleteWorld(
    api: SurfPolarPaperApi,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    TODO("Task 8")
}
