package dev.brewkits.kmpworkmanager.sample.stats

import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

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

    /**
     * A [TelemetryHook] that feeds the dashboard, optionally chaining to [delegate] so an
     * app can keep its own logging hook.
     *
     * This is the correct source for dashboard statistics, and it replaces an earlier
     * `TaskEventBus` subscription that got two things wrong:
     *
     * - **Durations were always 0.** `TaskCompletionEvent` carries no duration, so the old
     *   code dug for an optional `outputData["duration"]` that almost no worker sets. The
     *   dashboard showed "0ms" for every task while the library knew the real figure all
     *   along — [TelemetryHook.TaskCompletedEvent.durationMs].
     * - **Every task counted twice.** The bus carries both the library's own completion
     *   event and the one this demo's workers emit for the snackbar, and after
     *   `substringAfterLast('.')` the two are indistinguishable. The telemetry hook is
     *   called once per execution, by the library only.
     *
     * Install it at initialization, not from composition: the hook has to be in place
     * before the first task runs, and the dashboard should not miss work that happened
     * while its screen was not on-screen.
     */
    fun asTelemetryHook(delegate: TelemetryHook? = null): TelemetryHook =
        object : TelemetryHook {
            override fun onTaskScheduled(event: TelemetryHook.TaskScheduledEvent) {
                delegate?.onTaskScheduled(event)
            }

            override fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) {
                delegate?.onTaskStarted(event)
                scope.launch {
                    recordTaskStart(
                        taskId = executionKey(event.taskName, event.chainId, event.stepIndex),
                        taskName = event.taskName.substringAfterLast('.')
                    )
                }
            }

            override fun onTaskCompleted(event: TelemetryHook.TaskCompletedEvent) {
                delegate?.onTaskCompleted(event)
                scope.launch {
                    complete(
                        key = executionKey(event.taskName, event.chainId, event.stepIndex),
                        name = event.taskName,
                        success = event.success,
                        durationMs = event.durationMs
                    )
                }
            }

            override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
                delegate?.onTaskFailed(event)
                scope.launch {
                    complete(
                        key = executionKey(event.taskName, event.chainId, event.stepIndex),
                        name = event.taskName,
                        success = false,
                        durationMs = event.durationMs
                    )
                }
            }

            override fun onChainCompleted(event: TelemetryHook.ChainCompletedEvent) {
                delegate?.onChainCompleted(event)
            }

            override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
                delegate?.onChainFailed(event)
            }

            override fun onChainSkipped(event: TelemetryHook.ChainSkippedEvent) {
                delegate?.onChainSkipped(event)
            }
        }

    /**
     * Distinguishes concurrent runs of the same worker. A parallel chain step runs the same
     * worker class several times at once; keying on the name alone would make each start
     * overwrite the previous one's entry, so the chain and step index come along.
     */
    private fun executionKey(taskName: String, chainId: String?, stepIndex: Int?): String =
        if (chainId == null) taskName else "$taskName@$chainId#$stepIndex"

    private suspend fun complete(key: String, name: String, success: Boolean, durationMs: Long) {
        // A task can complete without this manager having seen it start — the hook is
        // installed at init, but a task enqueued by a previous process launch resumes
        // without a fresh start event. Synthesise the start so the completion is still
        // counted rather than silently dropped.
        if (!isTracking(key)) {
            recordTaskStart(taskId = key, taskName = name.substringAfterLast('.'))
        }
        recordTaskComplete(taskId = key, success = success, duration = durationMs)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private suspend fun isTracking(taskId: String): Boolean =
        mutex.withLock { activeExecutions.containsKey(taskId) }

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
