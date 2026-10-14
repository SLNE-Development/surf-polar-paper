package dev.slne.surf.polarpaper.core.client.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
data class WorldLockingConfig(
    @Comment("Locks worlds so each is only loaded on one server at a time. Needs RabbitMQ and the microservice.")
    val enabled: Boolean = true
)
