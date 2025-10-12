package com.example.kmpworkmanagerv2.debug

/**
 * A data class representing the information of a single background task for display on the debug screen.
 */
data class DebugTaskInfo(
    val id: String,
    val type: String, // e.g., "OneTime", "Periodic", "Chain"
    val status: String, // e.g., "ENQUEUED", "RUNNING", "SUCCEEDED"
    val workerClassName: String,
    val isPeriodic: Boolean = false,
    val isChain: Boolean = false
)

/**
 * An interface defining the contract for a platform-specific source
 * that can query the list of all known background tasks.
 */
interface DebugSource {
    /**
     * Asynchronously retrieves a list of all background tasks and their current status.
     * @return A list of [DebugTaskInfo] objects.
     */
    suspend fun getTasks(): List<DebugTaskInfo>
}
