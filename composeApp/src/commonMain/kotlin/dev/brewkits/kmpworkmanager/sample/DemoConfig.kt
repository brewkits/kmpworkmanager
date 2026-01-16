package dev.brewkits.kmpworkmanager.sample

/**
 * Demo configuration for displaying current DI approach.
 *
 * This is populated by platform-specific implementations to show
 * which build variant is currently running.
 */
expect object DemoConfig {
    /**
     * Returns the current DI approach name.
     *
     * Examples: "Manual", "Koin", "Hilt"
     */
    fun getApproachName(): String

    /**
     * Returns a description of the current approach.
     */
    fun getApproachDescription(): String
}
