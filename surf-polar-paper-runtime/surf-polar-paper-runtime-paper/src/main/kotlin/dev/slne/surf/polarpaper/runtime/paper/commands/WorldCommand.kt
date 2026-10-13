package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.kotlindsl.commandAPICommand

const val WORLD_PERMISSION = "surf.polarpaper.runtime.command.world"

fun worldCommand() = commandAPICommand("sworld") {
    withPermission(WORLD_PERMISSION)

    createWorldCommand()
    deleteWorldCommand()
    joinWorldCommand()
    loadWorldCommand()
    unloadWorldCommand()
}