package dev.slne.surf.polarpaper.runtime.paper

/**
 * Finds the name of this server, which world locks are held under. Sources in order: the surf-core-paper plugin, the
 * environment variable [ENVIRONMENT_VARIABLE] and the system property [SYSTEM_PROPERTY]. The first non-blank answer wins.
 */
class ServerNameResolver(
    /** Null when surf-core-paper is not installed. Only called first, so other sources work without it. */
    private val surfCoreName: () -> String?,
    private val environment: (String) -> String? = System::getenv,
    private val property: (String) -> String? = System::getProperty
) {
    fun resolve(): String {
        surfCoreName().clean()?.let { return it }
        environment(ENVIRONMENT_VARIABLE).clean()?.let { return it }
        property(SYSTEM_PROPERTY).clean()?.let { return it }

        throw IllegalStateException(
            "The server name is unknown. Install surf-core-paper, or set the environment variable " +
                    "$ENVIRONMENT_VARIABLE, or set the system property $SYSTEM_PROPERTY"
        )
    }

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val ENVIRONMENT_VARIABLE = "SURF_POLAR_PAPER_SERVER_NAME"
        const val SYSTEM_PROPERTY = "surf.polarpaper.server-name"
    }
}
