package dev.slne.surf.polarpaper.runtime.paper

import com.google.auto.service.AutoService
import dev.slne.surf.core.api.common.SurfCoreApi
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import java.nio.file.Path

@AutoService(SurfPolarPaperInstance::class)
class SurfPolarPaperInstancePaper : SurfPolarPaperInstance() {
    override val dataPath: Path get() = plugin.dataPath

    // surf-core-paper reads it from its server config during bootstrap, before any plugin loads
    override val serverName: String get() = SurfCoreApi.getCurrentServerName()
}