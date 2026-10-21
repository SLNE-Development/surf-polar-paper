package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import org.bukkit.entity.Player

fun CommandAPICommand.createWorldCommand() = subcommand("create") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments

        createWorld(TODO("Task 8"), player, gameModeIdentifier)
    }
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun createWorld(
    api: SurfPolarPaperApi,
    player: Player,
    gameModeIdentifier: String
) {
    TODO("Task 8")
}
