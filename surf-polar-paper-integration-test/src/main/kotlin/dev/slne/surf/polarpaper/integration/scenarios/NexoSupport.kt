package dev.slne.surf.polarpaper.integration.scenarios

import com.nexomc.nexo.api.NexoFurniture
import com.nexomc.nexo.mechanics.furniture.seats.FurnitureSeat
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay

/** Every Nexo class the scenarios touch, kept apart so nothing here loads on a server without Nexo. */
internal object NexoSupport {
    fun isInstalled(): Boolean = Bukkit.getPluginManager().isPluginEnabled("Nexo")

    /** Places [id] like Nexo does for a player, without the space check, with [yaw] as the player's rotation. */
    fun place(id: String, location: Location, yaw: Float, face: BlockFace): ItemDisplay {
        val mechanic = NexoFurniture.furnitureMechanic(id) ?: error("Nexo does not know the furniture $id")
        return mechanic.place(location, yaw, face, false) ?: error("Nexo did not place the furniture $id at $location")
    }

    fun furnitureId(entity: Entity): String? {
        if (entity !is ItemDisplay || !NexoFurniture.isFurniture(entity as Entity)) return null
        return NexoFurniture.furnitureMechanic(entity as Entity)?.itemID
    }

    fun isSeat(entity: Entity): Boolean = FurnitureSeat.isSeat(entity)
}
