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
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.joinWorldCommand() = subcommand("join") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        joinWorld(SurfPolarPaperApi.INSTANCE, player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun joinWorld(
    api: SurfPolarPaperApi,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    // loadWorld returns the already loaded world, so joining also works for worlds that are only stored
    val world = loadWorldOrFail(api, worldUuid, gameModeIdentifier)

    val platformWorld = api.getPlatformWorld<World>(world)
        ?: throw CommandAPI.failWithString("World with UUID '$worldUuid' and game mode '$gameModeIdentifier' is not loaded.")

    player.teleportAsync(platformWorld.spawnLocation)

    player.sendText {
        success("Teleported to world ")
        append(world)
        success(" successfully.")
    }
}
