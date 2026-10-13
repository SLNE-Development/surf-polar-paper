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
import dev.slne.surf.polarpaper.api.world.SurfWorld

private val paginationCache = Caffeine.newBuilder()
    .maximumSize(1000)
    .build<String, Pagination<SurfWorld>> { gameModeIdentifier ->
        Pagination {
            title {
                info("Worlds List - $gameModeIdentifier")
            }

            resultsPerPage = 5
            rowRenderer { world, i ->
                listOf(buildText {
                    append(world)
                    appendSpace()

                    append {
                        variableValue("[JOIN]")
                        clickRunsCommand("/sworld join ${world.gameModeIdentifier} ${world.uuid}")
                    }
                })
            }
        }
    }

fun CommandAPICommand.listWorldsCommand() = subcommand("list") {
    stringArgument("gameModeIdentifier")
    booleanArgument("showLoadedOnly", true)

    playerExecutorSuspend { player, arguments ->
        val gameModeIdentifier: String by arguments
        val pagination = paginationCache.get(gameModeIdentifier)

        val showLoadedOnly: Boolean? by arguments
        val worlds = SurfPolarPaperApi.listWorlds(gameModeIdentifier, showLoadedOnly ?: true)

        player.sendText {
            append(pagination.renderComponent(worlds))
        }
    }
}