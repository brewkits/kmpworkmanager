package dev.brewkits.kmpworkmanager.background.domain

/**
 * iOS Background Task types with different time limits
 *
 * **BGAppRefreshTask:**
 * - Time limit: ~30 seconds
 * - Frequency: System determines (typically every few hours)
 * - Use for: Quick sync, lightweight updates
 * - Restrictions: More aggressive time limit
 *
 * **BGProcessingTask:**
 * - Time limit: 5-10 minutes (up to 30 minutes on power + WiFi)
 * - Frequency: Less frequent, runs when system has resources
 * - Use for: Heavy processing, large uploads/downloads
 * - Restrictions: May not run if battery low or device in use
 *
 * @see [Apple Documentation](https://developer.apple.com/documentation/backgroundtasks)
 */
enum class BGTaskType {
    /**
     * BGAppRefreshTask - Quick background refresh
     * - Time limit: ~30 seconds
     * - Task timeout: 20 seconds (safety margin)
     * - Chain timeout: 50 seconds (not typically used for chains)
     */
    APP_REFRESH,

    /**
     * BGProcessingTask - Heavy background processing
     * - Time limit: 5-10 minutes (300-600 seconds)
     * - Task timeout: 120 seconds (2 minutes per task)
     * - Chain timeout: 300 seconds (5 minutes for full chain)
     */
    PROCESSING
}
