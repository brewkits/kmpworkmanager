package dev.brewkits.kmpworkmanager.background.domain

/**
 * Worker diagnostics interface for debugging "why didn't my task run?"
 * v2.2.2+ feature to improve developer experience
 *
 * **Use cases:**
 * - Debug screen in sample app
 * - Production monitoring dashboards
 * - Customer support diagnostics
 * - Automated health checks
 *
 * **Example:**
 * ```kotlin
 * val diagnostics = WorkerDiagnostics.getInstance()
 * val health = diagnostics.getSystemHealth()
 *
 * if (health.isLowPowerMode) {
 *     println("BGTasks may be throttled - device in low power mode")
 * }
 * if (health.isStorageLow) {
 *     println("Storage critical - tasks may fail")
 * }
 * ```
 */
interface WorkerDiagnostics {
    /**
     * Get current scheduler status
     * @return Scheduler status snapshot
     */
    suspend fun getSchedulerStatus(): SchedulerStatus

    /**
     * Get system health report (battery, storage, network, power mode)
     * @return System health snapshot
     */
    suspend fun getSystemHealth(): SystemHealthReport

    /**
     * Get detailed status for a specific task
     * @param id Task ID
     * @return Task status or null if not found
     */
    suspend fun getTaskStatus(id: String): TaskStatusDetail?
}

/**
 * Scheduler readiness and queue status
 */
data class SchedulerStatus(
    /**
     * Is scheduler initialized and ready?
     */
    val isReady: Boolean,

    /**
     * Total pending tasks in queue
     */
    val totalPendingTasks: Int,

    /**
     * Queue size (chain queue for iOS, work queue for Android)
     */
    val queueSize: Int,

    /**
     * Platform identifier (ios, android)
     */
    val platform: String,

    /**
     * Timestamp of status snapshot (epoch milliseconds)
     */
    val timestamp: Long
)

/**
 * System health metrics affecting task execution
 */
data class SystemHealthReport(
    /**
     * Timestamp of health check (epoch milliseconds)
     */
    val timestamp: Long,

    /**
     * Battery level (0-100%)
     */
    val batteryLevel: Int,

    /**
     * Is device charging?
     */
    val isCharging: Boolean,

    /**
     * Is network available?
     */
    val networkAvailable: Boolean,

    /**
     * Available storage (bytes)
     */
    val storageAvailable: Long,

    /**
     * Is storage critically low? (<500MB)
     */
    val isStorageLow: Boolean,

    /**
     * iOS: Is device in low power mode?
     * Android: Always false
     */
    val isLowPowerMode: Boolean,

    /**
     * Android: Is device in doze mode?
     * iOS: Always false
     */
    val deviceInDozeMode: Boolean
)

/**
 * Detailed status for a specific task
 */
data class TaskStatusDetail(
    /**
     * Task ID
     */
    val taskId: String,

    /**
     * Worker class name
     */
    val workerClassName: String,

    /**
     * Current state (PENDING, RUNNING, COMPLETED, FAILED)
     */
    val state: String,

    /**
     * Number of retry attempts
     */
    val retryCount: Int,

    /**
     * Last execution timestamp (epoch milliseconds, null if never executed)
     */
    val lastExecutionTime: Long?,

    /**
     * Last error message (null if no error)
     */
    val lastError: String?
)
