import dev.slne.surf.api.gradle.util.registerRequired

plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.polarpaper.runtime.paper.PaperMain")

    serverDependencies {
        registerRequired("polarpaper")
    }

    testing {
        // MockBukkit is not published for this Paper version, Bukkit is mocked with MockK instead
        mockBukkit = false
    }
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreClient)
    compileOnlyApi("live.minehub:polarpaper:2.3.1")

    testImplementation("live.minehub:polarpaper:2.3.1")
}
