package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPICommand

const val WORLD_PERMISSION = "surf.polarpaper.runtime.command.world"

fun buildWorldCommand(): CommandAPICommand = CommandAPICommand("sworld").apply {
    withPermission(WORLD_PERMISSION)

    createWorldCommand()
    deleteWorldCommand()
    joinWorldCommand()
    listWorldsCommand()
    loadWorldCommand()
    unloadWorldCommand()
}

fun worldCommand() = buildWorldCommand().register()
