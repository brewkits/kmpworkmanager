package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressBus
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.*
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Executes a single, non-chained background task on the iOS platform.
 */
class SingleTaskExecutor(private val workerFactory: IosWorkerFactory) {

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }

    /**
     * Creates and runs a worker based on its class name with timeout protection.
     */
    suspend fun executeTask(
        workerClassName: String,
        input: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): WorkerResult {
        Logger.i(LogTags.WORKER, "Executing task: $workerClassName (timeout: ${timeoutMs}ms)")

        val worker = try {
            workerFactory.createWorker(workerClassName)
        } catch (e: IllegalArgumentException) {
            Logger.e(LogTags.WORKER, "Worker not registered: $workerClassName — ${e.message}")
            val result = WorkerResult.Failure("Worker not registered: $workerClassName")
            emitEvent(workerClassName, result)
            return result
        } ?: run {
            Logger.e(LogTags.WORKER, "Worker not found: $workerClassName")
            val result = WorkerResult.Failure("Worker not found: $workerClassName")
            emitEvent(workerClassName, result)
            return result
        }

        return try {
            withTimeout(timeoutMs) {
                val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val currentJob = currentCoroutineContext()[Job]
                
                val env = WorkerEnvironment(
                    progressListener = object : ProgressListener {
                        override fun onProgressUpdate(progress: WorkerProgress) {
                            coroutineScope.launch {
                                TaskProgressBus.emit(
                                    TaskProgressEvent(
                                        taskId = workerClassName,
                                        taskName = workerClassName.substringAfterLast('.'),
                                        progress = progress
                                    )
                                )
                            }
                        }
                    },
                    isCancelled = { currentJob?.isCancelled == true }
                )

                val result = worker.doWork(input, env)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime

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
            val result = WorkerResult.Failure("Timed out after ${timeoutMs}ms")
            emitEvent(workerClassName, result)
            result
        } catch (e: CancellationException) {
            // CancellationException MUST be rethrown — swallowing it prevents the parent
            // coroutine scope from cancelling correctly, causing resource leaks.
            Logger.w(LogTags.WORKER, "Task cancelled by coroutine scope: $workerClassName")
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Task threw exception: $workerClassName", e)
            val result = WorkerResult.Failure("Exception: ${e.message}")
            emitEvent(workerClassName, result)
            result
        }
    }

    /**
     * Emit task completion event to TaskEventBus for UI notification
     *
     * Emits both success and failure events with outputData
     */
    private fun emitEvent(workerClassName: String, result: WorkerResult) {
        coroutineScope.launch {
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
