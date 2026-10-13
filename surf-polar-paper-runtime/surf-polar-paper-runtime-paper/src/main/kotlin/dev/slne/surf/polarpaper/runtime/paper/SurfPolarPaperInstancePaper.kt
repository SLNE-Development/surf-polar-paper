package dev.slne.surf.polarpaper.runtime.paper

import com.google.auto.service.AutoService
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import java.nio.file.Path

@AutoService(SurfPolarPaperInstance::class)
class SurfPolarPaperInstancePaper : SurfPolarPaperInstance() {
    override val dataPath: Path get() = plugin.dataPath
}