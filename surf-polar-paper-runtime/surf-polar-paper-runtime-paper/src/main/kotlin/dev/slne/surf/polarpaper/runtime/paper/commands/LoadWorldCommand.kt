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
import dev.slne.surf.polarpaper.api.world.SurfWorld
import kotlinx.coroutines.CancellationException
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.loadWorldCommand() = subcommand("load") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        loadWorld(SurfPolarPaperApi.INSTANCE, player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun loadWorld(
    api: SurfPolarPaperApi,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    val world = loadWorldOrFail(api, worldUuid, gameModeIdentifier)

    player.sendText {
        success("World ")
        append(world)
        success(" loaded successfully.")
    }
}

internal suspend fun loadWorldOrFail(
    api: SurfPolarPaperApi,
    worldUuid: UUID,
    gameModeIdentifier: String
): SurfWorld = try {
    api.loadWorld(worldUuid, gameModeIdentifier)
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    throw CommandAPI.failWithString("Failed to load world with UUID '$worldUuid' and game mode '$gameModeIdentifier': ${exception.message}")
}
