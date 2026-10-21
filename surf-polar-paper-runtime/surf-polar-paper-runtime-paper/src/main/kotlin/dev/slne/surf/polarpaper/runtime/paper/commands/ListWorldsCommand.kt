package dev.slne.surf.polarpaper.runtime.paper.commands

import com.github.benmanes.caffeine.cache.Caffeine
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.booleanArgument
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.core.messages.pagination.Pagination
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.ApiInstances
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import org.bukkit.entity.Player

private val paginationCache = Caffeine.newBuilder()
    .maximumSize(1000)
    .build<String, Pagination<WorldInfo>> { gameModeIdentifier ->
        Pagination {
            title {
                info("Worlds List - $gameModeIdentifier")
            }

            resultsPerPage = 5
            rowRenderer { world, _ ->
                listOf(buildText {
                    append(world)
                    appendSpace()

                    append {
                        variableValue("[JOIN]")
                        clickRunsCommand(joinCommandFor(world))
                    }
                })
            }
        }
    }

internal fun joinCommandFor(world: WorldInfo) = "/sworld join ${world.gameModeIdentifier} ${world.uuid}"

fun CommandAPICommand.listWorldsCommand() = subcommand("list") {
    withPermission(WORLD_PERMISSION)

    stringArgument("gameModeIdentifier")
    booleanArgument("showLoadedOnly", true)

    playerExecutorSuspend(commandScope) { player, arguments ->
        val gameModeIdentifier: String by arguments
        val showLoadedOnly: Boolean? by arguments

        listWorlds(player, gameModeIdentifier, showLoadedOnly)
    }
}

/**
 * Lists across all API instances. The owner of the game mode lists its worlds; a game mode nobody owns is listed from
 * the loaded worlds of every instance, or from the server's storage, which all instances share.
 */
internal suspend fun worldsToList(
    gameModeIdentifier: String,
    showLoadedOnly: Boolean?,
    instances: List<SurfPolarPaperApiImpl> = ApiInstances.all,
    storage: () -> StorageProvider? = { SurfPolarPaperInstance.INSTANCE.storageProvider }
): List<WorldInfo> {
    // Same default as SurfPolarPaperApi.listWorlds
    val loadedOnly = showLoadedOnly ?: false

    instances.firstOrNull { gameModeIdentifier in it.settings.gameModes }?.let {
        return it.listWorlds(gameModeIdentifier, loadedOnly)
    }

    if (loadedOnly) {
        return instances.flatMap { it.loadedWorlds }
            .filter { it.gameModeIdentifier == gameModeIdentifier }
            .map { it.info() }
    }

    val provider = storage() ?: throw CommandAPI.failWithString("The world storage is not connected.")
    return provider.listWorlds(gameModeIdentifier)
}

internal suspend fun listWorlds(
    player: Player,
    gameModeIdentifier: String,
    showLoadedOnly: Boolean?
) {
    val worlds = worldsToList(gameModeIdentifier, showLoadedOnly)
    val pagination = paginationCache.get(gameModeIdentifier)

    player.sendText {
        append(pagination.renderComponent(worlds))
    }
}
