import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("dev.slne.surf.microservice")
}

surfMicroservice {
    withClientCommonApi()
    withRabbitModule(RabbitModule.CLIENT_API)
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreCommon)
    api("aws.sdk.kotlin:s3:1.9.14")

    // Chunk and world types are part of the client API. The server provides them at runtime.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}