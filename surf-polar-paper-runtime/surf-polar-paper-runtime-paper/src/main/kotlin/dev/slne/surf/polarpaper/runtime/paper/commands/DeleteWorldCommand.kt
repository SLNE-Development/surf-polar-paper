package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.jorel.commandapi.kotlindsl.uuidArgument
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.world.SurfWorld
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.deleteWorldCommand() = subcommand("delete") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        deleteWorld(SurfPolarPaperApi.INSTANCE, player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun deleteWorld(
    api: SurfPolarPaperApi,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    // Worlds that are not loaded can be deleted too, their bytes are not needed for that
    val world = api.findWorld(worldUuid, gameModeIdentifier)
        ?: SurfWorld(worldUuid, gameModeIdentifier, ByteArray(0))

    api.deleteWorld(world)

    player.sendText {
        success("World ")
        append(world)
        success(" deleted successfully.")
    }
}
