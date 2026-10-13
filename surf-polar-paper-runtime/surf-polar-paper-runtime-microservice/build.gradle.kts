import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("dev.slne.surf.microservice")
}

surfCoreApi {
    withSurfRedis()
    withSurfDatabaseR2dbc("2.3.4", "dev.slne.surf.polarpaper.runtime.libs.database")
}

surfMicroservice {
    withMicroserviceApi()
    withRabbitModule(RabbitModule.SERVER_API, true)
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreCommon)

    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}