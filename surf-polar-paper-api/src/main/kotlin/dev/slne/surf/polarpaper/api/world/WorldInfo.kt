package dev.slne.surf.polarpaper.api.world

import dev.slne.surf.api.core.messages.adventure.buildText
import dev.slne.surf.api.core.serializer.java.uuid.SerializableUUID
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike

/**
 * Identifies a stored world without carrying its bytes, as returned by world listings.
 */
@Serializable
data class WorldInfo(
    val uuid: SerializableUUID,
    val gameModeIdentifier: String
) : ComponentLike {
    override fun asComponent(): Component = buildText {
        variableValue("$gameModeIdentifier-$uuid")
    }
}
