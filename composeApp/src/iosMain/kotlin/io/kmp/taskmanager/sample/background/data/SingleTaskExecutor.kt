package io.kmp.taskmanager.sample.background.data

import io.kmp.taskmanager.sample.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.sample.background.domain.TaskEventBus
import io.kmp.taskmanager.sample.utils.Logger
import io.kmp.taskmanager.sample.utils.LogTags
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
     * @param workerClassName The fully qualified name of the worker class.
     * @param input Optional input data for the worker.
     * @param timeoutMs Maximum execution time in milliseconds (default: 25s)
     * @return `true` if the work succeeded, `false` otherwise.
     */
    suspend fun executeTask(
        workerClassName: String,
        input: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        Logger.i(LogTags.WORKER, "Executing task: $workerClassName (timeout: ${timeoutMs}ms)")

        val worker = workerFactory.createWorker(workerClassName)
        if (worker == null) {
            Logger.e(LogTags.WORKER, "Failed to create worker: $workerClassName")
            emitFailureEvent(workerClassName, "Worker factory returned null")
            return false
        }

        return try {
            withTimeout(timeoutMs) {
                val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val result = worker.doWork(input)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime

                if (result) {
                    Logger.i(LogTags.WORKER, "Task completed successfully: $workerClassName (${duration}ms)")
                } else {
                    Logger.w(LogTags.WORKER, "Task completed with failure: $workerClassName (${duration}ms)")
                }

                result
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.WORKER, "Task timed out after ${timeoutMs}ms: $workerClassName")
            emitFailureEvent(workerClassName, "Timed out after ${timeoutMs}ms")
            false
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Task threw exception: $workerClassName", e)
            emitFailureEvent(workerClassName, "Exception: ${e.message}")
            false
        }
    }

    /**
     * Emit failure event to TaskEventBus for UI notification
     */
    private fun emitFailureEvent(workerClassName: String, reason: String) {
        CoroutineScope(Dispatchers.Main).launch {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName.substringAfterLast('.'),
                    success = false,
                    message = "❌ Failed: $reason"
                )
            )
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
