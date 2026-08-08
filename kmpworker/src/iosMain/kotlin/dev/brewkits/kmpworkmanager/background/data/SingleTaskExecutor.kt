package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressBus
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.*
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
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
     *
     * @param taskId The id passed to `scheduler.enqueue(id, ...)`, used as
     *   [ExecutionRecord.chainId] (there is no separate chain concept for a single task —
     *   this mirrors Android's `BaseKmpWorker`, which uses the WorkManager work id the
     *   same way). Falls back to [workerClassName] when not supplied.
     */
    suspend fun executeTask(
        workerClassName: String,
        input: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        taskId: String? = null
    ): WorkerResult {
        Logger.i(LogTags.WORKER, "Executing task: $workerClassName (timeout: ${timeoutMs}ms)")

        val recordTaskId = taskId ?: workerClassName
        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        val worker = try {
            workerFactory.createWorker(workerClassName)
        } catch (e: IllegalArgumentException) {
            Logger.e(LogTags.WORKER, "Worker not registered: $workerClassName — ${e.message}")
            val result = WorkerResult.Failure("Worker not registered: $workerClassName")
            recordCompletion(recordTaskId, workerClassName, result, startTime)
            return result
        } ?: run {
            Logger.e(LogTags.WORKER, "Worker not found: $workerClassName")
            val result = WorkerResult.Failure("Worker not found: $workerClassName")
            recordCompletion(recordTaskId, workerClassName, result, startTime)
            return result
        }

        return try {
            withTimeout(timeoutMs) {
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
                    is WorkerResult.Retry -> {
                        // Single-task path has no re-enqueue surface — log loudly so the worker
                        // author knows their Retry signal will be observed but not acted on. Use
                        // the scheduler's enqueue API to re-arm if you want a real retry.
                        Logger.w(
                            LogTags.WORKER,
                            "Task requested retry but SingleTaskExecutor has no re-enqueue path: " +
                                "$workerClassName — ${result.reason} (${duration}ms)"
                        )
                    }
                }

                recordCompletion(recordTaskId, workerClassName, result, startTime)
                result
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.WORKER, "Task timed out after ${timeoutMs}ms: $workerClassName")
            val result = WorkerResult.Failure("Timed out after ${timeoutMs}ms")
            recordCompletion(recordTaskId, workerClassName, result, startTime)
            result
        } catch (e: CancellationException) {
            // CancellationException MUST be rethrown — swallowing it prevents the parent
            // coroutine scope from cancelling correctly, causing resource leaks. No record is
            // written: the task was pre-empted, not completed (same convention ChainExecutor
            // uses for its own CancellationException branch).
            Logger.w(LogTags.WORKER, "Task cancelled by coroutine scope: $workerClassName")
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Task threw exception: $workerClassName", e)
            val result = WorkerResult.Failure("Exception: ${e.message}")
            recordCompletion(recordTaskId, workerClassName, result, startTime)
            result
        }
    }

    /**
     * Persists the completion event and execution history record for a single task.
     *
     * LIFECYCLE: before 3.3.0 this fired via `coroutineScope.launch { TaskEventBus.emit(...) }`
     * — fire-and-forget on the executor's own scope, and the bus only, no persistence at all.
     * That meant (a) [cleanup] cancelling the scope in the same tick a caller moved past
     * `executeTask`'s return (e.g. the app backgrounds right after a task finishes) could lose
     * the emission entirely, and (b) nothing was ever written to [EventStore] or
     * [ExecutionHistoryStore] for a non-chained task — `getExecutionHistory()` only ever saw
     * chain executions on iOS. Awaiting this directly inside `executeTask`'s own coroutine
     * (wrapped `NonCancellable` so a late cancellation can't cut it off mid-write) guarantees
     * the record is durable before the caller observes the [WorkerResult] — matching
     * [ChainExecutor] and Android's `BaseKmpWorker`. See discussion #66 / issue #71.
     */
    private suspend fun recordCompletion(
        taskId: String,
        workerClassName: String,
        result: WorkerResult,
        startTime: Long
    ) {
        val shortName = workerClassName.substringAfterLast('.')
        val event = when (result) {
            is WorkerResult.Success -> TaskCompletionEvent(
                taskName = shortName,
                success = true,
                message = result.message ?: "Task completed successfully",
                outputData = result.data
            )
            is WorkerResult.Failure -> TaskCompletionEvent(
                taskName = shortName,
                success = false,
                message = result.message,
                outputData = null
            )
            is WorkerResult.Retry -> TaskCompletionEvent(
                taskName = shortName,
                success = false,
                message = "retry requested: ${result.reason}",
                outputData = null
            )
        }

        withContext(NonCancellable) {
            // Durable emission: persists to EventStore, then forwards to TaskEventBus —
            // same contract ChainExecutor and BaseKmpWorker already rely on.
            try {
                TaskEventManager.emit(event)
            } catch (e: Exception) {
                Logger.w(LogTags.WORKER, "Failed to emit completion event for $workerClassName: ${e.message}")
            }

            val endTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
            val status = if (result is WorkerResult.Success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILURE
            val errorMessage = when (result) {
                is WorkerResult.Success -> null
                is WorkerResult.Failure -> result.message
                is WorkerResult.Retry -> "retry requested: ${result.reason}"
            }
            val record = ExecutionRecord(
                id = NSUUID().UUIDString,
                // No chain concept for a single task — the task's own id fills this slot,
                // exactly as BaseKmpWorker uses `id.toString()` on Android.
                chainId = taskId,
                status = status,
                startedAtMs = startTime,
                endedAtMs = endTime,
                durationMs = endTime - startTime,
                totalSteps = 1,
                completedSteps = if (status == ExecutionStatus.SUCCESS) 1 else 0,
                failedStep = if (status != ExecutionStatus.SUCCESS) 0 else null,
                errorMessage = errorMessage,
                retryCount = 0,
                platform = "ios",
                workerClassNames = listOf(workerClassName)
            )
            try {
                KmpWorkManagerRuntime.executionHistoryStore?.save(record)
            } catch (e: Exception) {
                Logger.w(LogTags.WORKER, "Failed to save execution history record for $workerClassName: ${e.message}")
            }
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
