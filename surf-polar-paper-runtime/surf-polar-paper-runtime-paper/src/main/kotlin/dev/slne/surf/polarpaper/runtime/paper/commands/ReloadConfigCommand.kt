package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.core.messages.adventure.sendText
import dev.slne.surf.api.paper.command.executors.anyExecutorSuspend
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import kotlinx.coroutines.CancellationException
import org.bukkit.command.CommandSender

fun CommandAPICommand.reloadConfigCommand() = subcommand("reload") {
    withPermission(WORLD_PERMISSION)

    anyExecutorSuspend(commandScope) { sender, _ ->
        reloadConfig(SurfPolarPaperInstance.INSTANCE, sender)
    }
}

/**
 * Reloads the config from disk and reconnects the storage provider, so storage changes apply without a restart.
 */
internal suspend fun reloadConfig(
    instance: SurfPolarPaperInstance,
    sender: CommandSender
) {
    try {
        instance.reloadConfig(reconnect = true)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw CommandAPI.failWithString("Failed to reload the config: ${exception.message}")
    }

    sender.sendText {
        success("Config reloaded, now using ")
        variableValue(instance.config.storage.storageType.name)
        success(" storage.")
    }
}
