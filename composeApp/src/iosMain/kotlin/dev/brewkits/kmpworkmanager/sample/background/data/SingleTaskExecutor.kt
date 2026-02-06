package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.stats.TaskStatsManager
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.*
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Executes a single, non-chained background task on the iOS platform.
 *
 * Features:
 * - Automatic timeout protection (25s for BGAppRefreshTask, 55s for BGProcessingTask)
 * - Comprehensive error handling and logging
 * - Task completion event emission
 * - Memory-safe coroutine scope management
 */
class SingleTaskExecutor(private val workerFactory: IosWorkerFactory) {

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        /**
         * Default timeout for task execution (25 seconds)
         * Provides 5s safety margin for BGAppRefreshTask (30s limit)
         * BGProcessingTask has 60s limit, so this is even safer
         */
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }

    /**
     * Creates and runs a worker based on its class name with timeout protection.
     *
     * v2.3.0+: Returns WorkerResult with data instead of Boolean
     *
     * @param workerClassName The fully qualified name of the worker class.
     * @param input Optional input data for the worker.
     * @param timeoutMs Maximum execution time in milliseconds (default: 25s)
     * @return WorkerResult with success/failure status and optional data
     */
    suspend fun executeTask(
        workerClassName: String,
        input: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): WorkerResult {
        Logger.i(LogTags.WORKER, "Executing task: $workerClassName (timeout: ${timeoutMs}ms)")

        val worker = workerFactory.createWorker(workerClassName)
        if (worker == null) {
            Logger.e(LogTags.WORKER, "Failed to create worker: $workerClassName")
            val result = WorkerResult.Failure("Worker factory returned null")
            emitEvent(workerClassName, result)
            return result
        }

        val taskName = workerClassName.substringAfterLast('.')
        val taskId = "$taskName-${(NSDate().timeIntervalSince1970 * 1000).toLong()}"
        TaskStatsManager.recordTaskStart(taskId, taskName)

        return try {
            withTimeout(timeoutMs) {
                val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val result = worker.doWork(input)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime

                val success = result is WorkerResult.Success
                TaskStatsManager.recordTaskComplete(taskId, success, duration)

                when (result) {
                    is WorkerResult.Success -> {
                        Logger.i(LogTags.WORKER, "Task completed successfully: $workerClassName (${duration}ms)")
                    }
                    is WorkerResult.Failure -> {
                        Logger.w(LogTags.WORKER, "Task completed with failure: $workerClassName (${duration}ms)")
                    }
                }

                // Emit event with result data
                emitEvent(workerClassName, result)
                result
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.WORKER, "Task timed out after ${timeoutMs}ms: $workerClassName")
            TaskStatsManager.recordTaskComplete(taskId, false, timeoutMs)
            val result = WorkerResult.Failure("Timed out after ${timeoutMs}ms")
            emitEvent(workerClassName, result)
            result
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Task threw exception: $workerClassName", e)
            TaskStatsManager.recordTaskComplete(taskId, false, 0L)
            val result = WorkerResult.Failure("Exception: ${e.message}")
            emitEvent(workerClassName, result)
            result
        }
    }

    /**
     * Emit task completion event to TaskEventBus for UI notification
     *
     * v2.3.0+: Emits both success and failure events with outputData
     */
    private fun emitEvent(workerClassName: String, result: WorkerResult) {
        CoroutineScope(Dispatchers.Main).launch {
            val event = when (result) {
                is WorkerResult.Success -> {
                    TaskCompletionEvent(
                        taskName = workerClassName.substringAfterLast('.'),
                        success = true,
                        message = result.message ?: "Task completed successfully",
                        outputData = result.data
                    )
                }
                is WorkerResult.Failure -> {
                    TaskCompletionEvent(
                        taskName = workerClassName.substringAfterLast('.'),
                        success = false,
                        message = result.message,
                        outputData = null
                    )
                }
            }
            TaskEventBus.emit(event)
        }
    }

    /**
     * Cleanup coroutine scope (call when executor is no longer needed)
     */
    fun cleanup() {
        Logger.d(LogTags.WORKER, "Cleaning up SingleTaskExecutor")
        job.cancel()
    }
}
