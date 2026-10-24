package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.jorel.commandapi.kotlindsl.uuidArgument
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.core.client.ApiInstances
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import org.bukkit.entity.Player
import java.util.*

fun CommandAPICommand.joinWorldCommand() = subcommand("join") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    uuidArgument("worldUuid")

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val worldUuid: UUID by arguments

        joinWorld(apiForJoin(worldUuid, gameModeIdentifier), player, worldUuid, gameModeIdentifier)
    }
}

/** The instance that has the world loaded, otherwise the one that owns its game mode and can load it. */
internal fun apiForJoin(
    worldUuid: UUID,
    gameModeIdentifier: String,
    instances: () -> List<SurfPolarPaperApiImpl> = { ApiInstances.all }
): SurfPolarPaperApiImpl =
    instances().firstOrNull { it.findWorld(worldUuid, gameModeIdentifier) != null }
        ?: ownerOrFail(gameModeIdentifier) { id -> instances().firstOrNull { id in it.settings.gameModes } }

internal suspend fun joinWorld(
    api: SurfPolarPaperApiImpl,
    player: Player,
    worldUuid: UUID,
    gameModeIdentifier: String
) {
    // loadWorld returns the already loaded world, so joining also works for worlds that are only stored
    val world = loadWorldOrFail(api, worldUuid, gameModeIdentifier)

    val platformWorld = platformWorldOrFail(
        world,
        "World with UUID '$worldUuid' and game mode '$gameModeIdentifier' is not loaded."
    )

    player.teleportAsync(platformWorld.spawnLocation)

    player.sendText {
        success("Teleported to world ")
        append(world)
        success(" successfully.")
    }
}
