package dev.slne.surf.polarpaper.integration

/** One check of the real-server run. It owns its game mode, so scenarios do not influence each other. */
abstract class Scenario(val name: String) {
    /** A reason to skip this scenario, e.g. a missing furniture plugin, or null to run it. */
    open fun skipReason(): String? = null

    abstract suspend fun run(context: ScenarioContext)
}

/** Thrown by a scenario for a failed expectation. The message says what was expected and what was found. */
class ScenarioFailure(message: String) : AssertionError(message)

fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw ScenarioFailure(message())
}

fun <T> expectEquals(expected: T, actual: T, what: String) {
    if (expected != actual) throw ScenarioFailure("$what: expected <$expected> but was <$actual>")
}

enum class Outcome { PASS, FAIL, SKIPPED }

data class ScenarioResult(
    val name: String,
    val outcome: Outcome,
    val message: String? = null,
    val stackTrace: String? = null,
    val durationMillis: Long = 0
)
