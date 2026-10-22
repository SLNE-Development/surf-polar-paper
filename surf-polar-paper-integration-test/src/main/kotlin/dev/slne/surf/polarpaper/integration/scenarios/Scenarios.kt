package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.integration.IntegrationTestPlugin
import dev.slne.surf.polarpaper.integration.Scenario

/** All scenarios in the order they run. */
fun allScenarios(plugin: IntegrationTestPlugin): List<Scenario> = listOf(
    EntitiesAndPdcRoundTrip(plugin),
    CustomDataTypeRoundTrip(),
    RawMigrationStrawberries(),
    MigrationChain(),
    LiveMigration(),
    NewerWorldRefused(),
    FailingMigration(),
    UnhandledSectionAborts(),
    UnhandledSectionAfterChunkUnload(),
    PipelineRealPolarCalls(),
    GeneratedChunkSurvivesUnload(plugin),
    CraftEngineRoundTrip(),
    NexoRoundTrip(plugin),
    NexoToCraftEngineToNexo(),
    InSessionUnloadReload(plugin)
)
