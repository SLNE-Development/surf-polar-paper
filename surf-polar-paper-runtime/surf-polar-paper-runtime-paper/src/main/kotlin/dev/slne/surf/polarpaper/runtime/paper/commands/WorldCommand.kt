package dev.slne.surf.polarpaper.runtime.paper.commands

import com.github.shynixn.mccoroutine.folia.scope
import dev.jorel.commandapi.CommandAPICommand
import dev.slne.surf.api.paper.command.executors.CoroutineScopeProvider
import dev.slne.surf.polarpaper.runtime.paper.plugin

const val WORLD_PERMISSION = "surf.polarpaper.runtime.command.world"

// Resolved when a command runs, instead of walking the stack for the calling plugin when it is built
internal val commandScope: CoroutineScopeProvider = { plugin.scope }

fun buildWorldCommand(): CommandAPICommand = CommandAPICommand("sworld").apply {
    withPermission(WORLD_PERMISSION)

    createWorldCommand()
    deleteWorldCommand()
    joinWorldCommand()
    listWorldsCommand()
    loadWorldCommand()
    reloadConfigCommand()
    unloadWorldCommand()
}

fun worldCommand() = buildWorldCommand().register()
