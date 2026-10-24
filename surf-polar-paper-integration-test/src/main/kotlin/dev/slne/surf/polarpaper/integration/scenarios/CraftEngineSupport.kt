package dev.slne.surf.polarpaper.integration.scenarios

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity

/**
 * Every CraftEngine class the scenarios touch. Kept apart from the scenarios so that nothing here is loaded on a
 * server without CraftEngine, where the CraftEngine scenarios are skipped.
 */
internal object CraftEngineSupport {
    const val FURNITURE_ID = "default:bench"
    const val BLOCK_ID = "default:chessboard_block"

    fun isInstalled(): Boolean = Bukkit.getPluginManager().isPluginEnabled("CraftEngine")

    fun placeFurniture(location: Location, id: String = FURNITURE_ID): Entity =
        CraftEngineFurniture.place(location, Key.of(id))?.bukkitEntity()
            ?: error("CraftEngine did not place the furniture $id at $location")

    fun placeBlock(location: Location, id: String = BLOCK_ID) {
        check(CraftEngineBlocks.place(location, Key.of(id), false)) { "CraftEngine did not place the block $id at $location" }
    }

    /** The custom block id and state properties CraftEngine sees at [block], or null when it is no custom block. */
    fun describeBlock(block: Block): String? {
        val state = CraftEngineBlocks.getCustomBlockState(block) ?: return null
        return "${state.owner().value().id().asString()}[${state.propertiesAsString}]"
    }

    fun isFurnitureBase(entity: Entity): Boolean = CraftEngineFurniture.isFurniture(entity)

    fun furnitureId(entity: Entity): String? =
        CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)?.id()?.asString()

    /** Whether CraftEngine tracks [entity] as the base entity of a live furniture piece. */
    fun isLiveFurniture(entity: Entity): Boolean = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity) != null

    fun isCollider(entity: Entity): Boolean = CraftEngineFurniture.isCollisionEntity(entity)

    fun isSeat(entity: Entity): Boolean = CraftEngineFurniture.isSeat(entity)
}
