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
        enabled = false
    }
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreClient)
    compileOnlyApi("live.minehub:polarpaper:2.3.1")
}