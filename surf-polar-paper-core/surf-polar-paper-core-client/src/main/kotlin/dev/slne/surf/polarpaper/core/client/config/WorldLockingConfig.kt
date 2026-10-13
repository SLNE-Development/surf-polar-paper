package dev.slne.surf.polarpaper.core.client.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.net.InetAddress

@ConfigSerializable
data class WorldLockingConfig(
    @Comment("Locks worlds so each is only loaded on one server at a time. Needs RabbitMQ and the microservice.")
    val enabled: Boolean = true,

    @Comment("Name this server holds world locks under, unique per server. Empty uses the hostname.")
    val serverName: String = ""
) {
    fun resolveServerName(): String = serverName.ifBlank {
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: InetAddress.getLocalHost().hostName
    }
}
