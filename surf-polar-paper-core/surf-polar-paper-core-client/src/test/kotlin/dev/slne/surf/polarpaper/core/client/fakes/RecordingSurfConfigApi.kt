package dev.slne.surf.polarpaper.core.client.fakes

import dev.slne.surf.api.core.config.SurfConfigApi
import dev.slne.surf.api.core.config.manager.SpongeConfigManager
import dev.slne.surf.api.core.config.migration.ConfigMigrationBuilder
import java.nio.file.Path

/**
 * Test service for [SurfConfigApi], whose real implementation only exists on the server.
 * Registered in META-INF/services and records which config managers are requested.
 */
class RecordingSurfConfigApi : SurfConfigApi {
    data class Request(val configClass: Class<*>, val folder: Path, val fileName: String)

    override fun <C> createSpongeYmlConfigManager(
        configClass: Class<C>,
        configFolder: Path,
        configFileName: String,
        migrationBuilder: ConfigMigrationBuilder
    ): SpongeConfigManager<C> {
        ymlManagerRequests += Request(configClass, configFolder, configFileName)
        throw UnsupportedOperationException("Config managers are not available in tests")
    }

    override fun <C> createSpongeYmlConfig(configClass: Class<C>, configFolder: Path, configFileName: String): C =
        unsupported()

    override fun <C> createSpongeJsonConfig(configClass: Class<C>, configFolder: Path, configFileName: String): C =
        unsupported()

    override fun <C> createSpongeJsonConfigManager(
        configClass: Class<C>,
        configFolder: Path,
        configFileName: String,
        migrationBuilder: ConfigMigrationBuilder
    ): SpongeConfigManager<C> = unsupported()

    override fun <C> getSpongeConfig(configClass: Class<C>): C = unsupported()

    override fun <C> reloadSpongeConfig(configClass: Class<C>): C = unsupported()

    override fun <C> getSpongeConfigManagerForConfig(configClass: Class<C>): SpongeConfigManager<C> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not available in tests")

    companion object {
        val ymlManagerRequests = mutableListOf<Request>()
    }
}
