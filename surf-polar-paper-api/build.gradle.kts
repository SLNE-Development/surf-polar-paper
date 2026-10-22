import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

publishing {
    repositories {
        slneReleases()
    }
}

dependencies {
    // Chunk, world and plugin types appear in the public API. The server provides them at runtime.
    compileOnlyApi("io.papermc.paper:paper-api:26.3.build.211-stable")
}
