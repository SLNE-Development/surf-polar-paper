package dev.slne.surf.polarpaper.runtime.paper.commands

import com.github.benmanes.caffeine.cache.Caffeine
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.booleanArgument
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.core.messages.pagination.Pagination
import dev.slne.surf.api.paper.command.executors.playerExecutorSuspend
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.world.WorldInfo
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

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val showLoadedOnly: Boolean? by arguments

        listWorlds(SurfPolarPaperApi.INSTANCE, player, gameModeIdentifier, showLoadedOnly)
    }
}

internal suspend fun listWorlds(
    api: SurfPolarPaperApi,
    player: Player,
    gameModeIdentifier: String,
    showLoadedOnly: Boolean?
) {
    // Same default as SurfPolarPaperApi.listWorlds
    val worlds = api.listWorlds(gameModeIdentifier, showLoadedOnly ?: false)
    val pagination = paginationCache.get(gameModeIdentifier)

    player.sendText {
        append(pagination.renderComponent(worlds))
    }
}
