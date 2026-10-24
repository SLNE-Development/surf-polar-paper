package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.jorel.commandapi.kotlindsl.uuidArgument
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.deleteWorldCommand() = subcommand("delete") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        deleteWorld(ownerOrFail(gameModeIdentifier), player, worldUuid, gameModeIdentifier)
    }
}

internal suspend fun deleteWorld(
    api: SurfPolarPaperApiImpl,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String,
    bridge: PlatformWorldBridge = PlatformWorldBridge.INSTANCE
) {
    // Worlds that are not loaded can be deleted too, only their identity is needed for that
    val world = api.findWorld(worldUuid, gameModeIdentifier)
        ?: SurfWorldImpl(worldUuid, gameModeIdentifier, 0, api, bridge)

    api.deleteWorld(world)

    player.sendText {
        success("World ")
        append(world)
        success(" deleted successfully.")
    }
}
