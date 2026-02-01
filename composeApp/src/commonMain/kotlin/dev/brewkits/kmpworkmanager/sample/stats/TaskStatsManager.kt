package dev.brewkits.kmpworkmanager.sample.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Statistics about task execution
 */
data class TaskStats(
    val totalExecuted: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val activeCount: Int = 0,
    val queueSize: Int = 0,
    val averageDuration: Long = 0
) {
    val successRate: Float
        get() = if (totalExecuted > 0) {
            (successCount.toFloat() / totalExecuted.toFloat()) * 100f
        } else 0f
}

/**
 * Task execution record for tracking task history
 */
data class TaskExecution(
    val taskId: String,
    val taskName: String,
    val startTime: Long,
    val endTime: Long? = null,
    val success: Boolean? = null,
    val duration: Long? = null
)

/**
 * Manages task statistics and execution history
 */
@OptIn(kotlin.time.ExperimentalTime::class)
object TaskStatsManager {
    private val mutex = Mutex()
    private val _stats = MutableStateFlow(TaskStats())
    private val _recentExecutions = MutableStateFlow<List<TaskExecution>>(emptyList())
    private val activeExecutions = mutableMapOf<String, TaskExecution>()
    private val completedDurations = mutableListOf<Long>()

    /**
     * Current task statistics
     */
    val stats: StateFlow<TaskStats> = _stats.asStateFlow()

    /**
     * Recent task executions (last 50)
     */
    val recentExecutions: StateFlow<List<TaskExecution>> = _recentExecutions.asStateFlow()

    /**
     * Record a task starting
     */
    suspend fun recordTaskStart(taskId: String, taskName: String = "Task") {
        mutex.withLock {
            val execution = TaskExecution(
                taskId = taskId,
                taskName = taskName,
                startTime = Clock.System.now().toEpochMilliseconds()
            )
            activeExecutions[taskId] = execution

            _stats.value = _stats.value.copy(
                activeCount = activeExecutions.size
            )
        }
    }

    /**
     * Record a task completing
     */
    suspend fun recordTaskComplete(taskId: String, success: Boolean, duration: Long) {
        mutex.withLock {
            val startExecution = activeExecutions.remove(taskId)
            val endTime = Clock.System.now().toEpochMilliseconds()

            if (startExecution != null) {
                val completedExecution = startExecution.copy(
                    endTime = endTime,
                    success = success,
                    duration = duration
                )

                // Add to recent executions (keep last 50)
                _recentExecutions.value = (_recentExecutions.value + completedExecution)
                    .takeLast(50)
            }

            // Track duration for average calculation
            completedDurations.add(duration)
            if (completedDurations.size > 100) {
                completedDurations.removeAt(0)
            }

            val avgDuration = if (completedDurations.isNotEmpty()) {
                completedDurations.average().toLong()
            } else 0L

            _stats.value = _stats.value.copy(
                totalExecuted = _stats.value.totalExecuted + 1,
                successCount = if (success) _stats.value.successCount + 1 else _stats.value.successCount,
                failureCount = if (!success) _stats.value.failureCount + 1 else _stats.value.failureCount,
                activeCount = activeExecutions.size,
                averageDuration = avgDuration
            )
        }
    }

    /**
     * Increment queue size
     */
    suspend fun incrementQueueSize() {
        mutex.withLock {
            _stats.value = _stats.value.copy(
                queueSize = _stats.value.queueSize + 1
            )
        }
    }

    /**
     * Decrement queue size
     */
    suspend fun decrementQueueSize() {
        mutex.withLock {
            _stats.value = _stats.value.copy(
                queueSize = maxOf(0, _stats.value.queueSize - 1)
            )
        }
    }

    /**
     * Reset all statistics
     */
    suspend fun reset() {
        mutex.withLock {
            activeExecutions.clear()
            completedDurations.clear()
            _stats.value = TaskStats()
            _recentExecutions.value = emptyList()
        }
    }

    /**
     * Get current active task count
     */
    fun getActiveTaskCount(): Int = _stats.value.activeCount

    /**
     * Get recent executions as a list
     */
    fun getRecentExecutions(): List<TaskExecution> = _recentExecutions.value
}
