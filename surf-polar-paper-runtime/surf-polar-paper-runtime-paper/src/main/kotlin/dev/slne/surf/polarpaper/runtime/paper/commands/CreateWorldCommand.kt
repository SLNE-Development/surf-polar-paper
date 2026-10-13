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
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.createWorldCommand() = subcommand("create") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments

        createWorld(SurfPolarPaperApi.INSTANCE, player, gameModeIdentifier)
    }
}

internal suspend fun createWorld(
    api: SurfPolarPaperApi,
    player: Player,
    gameModeIdentifier: String
) {
    val world = api.createWorld(
        worldUuid = UUID.randomUUID(),
        gameModeIdentifier = gameModeIdentifier,
        worldBytes = ByteArray(0)
    )

    val platformWorld = api.getPlatformWorld<World>(world)
        ?: throw CommandAPI.failWithString("Failed to create world for game mode '$gameModeIdentifier'.")

    player.teleportAsync(platformWorld.spawnLocation)

    player.sendText {
        success("World ")
        append(world)
        success(" created successfully.")
    }
}
