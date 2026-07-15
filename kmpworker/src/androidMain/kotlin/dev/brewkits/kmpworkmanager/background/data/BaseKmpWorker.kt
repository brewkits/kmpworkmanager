package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Base class for [KmpWorker] and [KmpHeavyWorker].
 */
abstract class BaseKmpWorker : CoroutineWorker {

    companion object {
        /**
         * Optional override for the notification title used when this worker is
         * promoted to a foreground service.
         */
        @Volatile
        var configNotificationTitle: String? = null
    }

    // @get:JvmName avoids clash with ListenableWorker.getWorkerFactory() (@RestrictTo LIBRARY_GROUP)
    // Without this, Kotlin generates a JVM getter named getWorkerFactory() which trips Lint.
    @get:JvmName("getKmpWorkerFactory")
    protected val workerFactory: AndroidWorkerFactory

    constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        workerFactory: AndroidWorkerFactory
    ) : super(appContext, workerParams) {
        this.workerFactory = workerFactory
    }

    @Deprecated(
        "Use the constructor that accepts a workerFactory parameter for proper DI support.",
        level = DeprecationLevel.WARNING
    )
    constructor(
        appContext: Context,
        workerParams: WorkerParameters
    ) : super(appContext, workerParams) {
        this.workerFactory = try {
            KmpWorkManagerKoin.getKoin().get()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "KmpWorkManager not initialized — worker cannot start. " +
                "Call KmpWorkManager.initialize() in Application.onCreate() before WorkManager runs, " +
                "or migrate to KmpWorkerFactory for proper constructor injection (see KmpWorkerFactory KDoc).",
                e
            )
        }
    }

    protected var overflowInputFile: java.io.File? = null
    protected open val workerLogTag: String get() = "BaseKmpWorker"

    protected suspend fun resolveInputJson(): String? {
        val overflowPath = inputData.getString(NativeTaskScheduler.KEY_INPUT_JSON_FILE)
        if (overflowPath != null) {
            val file = java.io.File(overflowPath)
            return try {
                if (withContext(kotlinx.coroutines.Dispatchers.IO) { file.exists() }) {
                    val content = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.bufferedReader().use { it.readText() }
                    }
                    overflowInputFile = file
                    content
                } else {
                    Logger.w(LogTags.WORKER, "Overflow input file missing: $overflowPath")
                    null
                }
            } catch (e: Exception) {
                Logger.e(LogTags.WORKER, "Failed to read overflow input file: $overflowPath", e)
                null
            }
        }
        return inputData.getString("inputJson")
    }

    protected fun checkBatteryGuard(): Boolean {
        val minBattery = KmpWorkManagerRuntime.minBatteryLevelPercent
        if (minBattery <= 0) return false
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        if (level in 0 until minBattery && !isCharging) {
            Logger.w(LogTags.WORKER, "🔋 $workerLogTag deferred — battery at $level% (min: $minBattery%)")
            return true
        }
        return false
    }

    protected abstract suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult

    protected suspend fun doWorkInternal(): Result {
        val workerClassName = inputData.getString("workerClassName")
            ?: return Result.failure()

        val startTime = System.currentTimeMillis()
        var isRetrying = false
        var wasCancelled = false
        var historyStatus: ExecutionStatus? = null
        var historyDuration = 0L

        KmpWorkManagerRuntime.notifyTaskStarted(
            TelemetryHook.TaskStartedEvent(
                taskName = workerClassName,
                platform = "android",
                startedAtMs = startTime
            )
        )

        try {
            val inputJson = resolveInputJson()

            if (checkBatteryGuard()) {
                isRetrying = true
                historyStatus = ExecutionStatus.FAILURE
                return Result.retry()
            }

            val result = performWork(workerClassName, inputJson)
            val duration = System.currentTimeMillis() - startTime
            historyDuration = duration

            when (result) {
                is WorkerResult.Success -> {
                    val message = result.message ?: "Worker completed successfully"
                    Logger.i(LogTags.WORKER, "$workerLogTag success: $workerClassName — $message")
                    
                    val completionEvent = TaskCompletionEvent(
                        taskName = workerClassName,
                        success = true,
                        message = message,
                        outputData = result.data
                    )
                    
                    TaskEventManager.emit(completionEvent)
                    KmpWorkManagerRuntime.notifyTaskCompleted(
                        TelemetryHook.TaskCompletedEvent(
                            taskName = workerClassName,
                            platform = "android",
                            success = true,
                            durationMs = duration
                        )
                    )
                    historyStatus = ExecutionStatus.SUCCESS
                    return Result.success()
                }
                is WorkerResult.Failure -> {
                    Logger.w(LogTags.WORKER, "$workerLogTag failure: $workerClassName — ${result.message}")

                    val completionEvent = TaskCompletionEvent(
                        taskName = workerClassName,
                        success = false,
                        message = result.message,
                        outputData = null
                    )

                    TaskEventManager.emit(completionEvent)
                    KmpWorkManagerRuntime.notifyTaskCompleted(
                        TelemetryHook.TaskCompletedEvent(
                            taskName = workerClassName,
                            platform = "android",
                            success = false,
                            durationMs = duration,
                            errorMessage = result.message
                        )
                    )
                    KmpWorkManagerRuntime.notifyTaskFailed(
                        TelemetryHook.TaskFailedEvent(
                            taskName = workerClassName,
                            platform = "android",
                            error = result.message,
                            durationMs = duration,
                            retryCount = runAttemptCount
                        )
                    )
                    // Honor an explicit retry ceiling stamped into inputData by the caller/plugin.
                    // Semantics: maxRetries=N → at most N+1 total runs (1 initial + N retries).
                    // runAttemptCount is 0-based, so retries are exhausted once runAttemptCount >= N.
                    // Absent key → -1 → uncapped (back-compat with existing WorkRequests).
                    // Mirrors iOS DynamicTaskDispatcher.handleOneTimeResult (effectiveCap = 1 +
                    // maxRetries) and the attemptCap ceiling used by the WorkerResult.Retry branch below.
                    val maxRetries = inputData.getInt(NativeTaskScheduler.KEY_MAX_RETRIES, -1)
                    val retriesExhausted = maxRetries in 0..runAttemptCount
                    val willRetry = result.shouldRetry && !retriesExhausted
                    historyStatus = if (willRetry) ExecutionStatus.FAILURE else ExecutionStatus.ABANDONED
                    if (willRetry) {
                        isRetrying = true
                        return Result.retry()
                    } else {
                        if (result.shouldRetry && retriesExhausted) {
                            Logger.w(
                                LogTags.WORKER,
                                "$workerLogTag retry cap reached for $workerClassName " +
                                    "(maxRetries=$maxRetries, runAttemptCount=$runAttemptCount). Marking as permanent failure."
                            )
                        }
                        return Result.failure()
                    }
                }
                is WorkerResult.Retry -> {
                    Logger.i(
                        LogTags.WORKER,
                        "$workerLogTag retry: $workerClassName — ${result.reason}" +
                            (result.delayMs?.let { " (delayMs=$it)" } ?: "") +
                            (result.attemptCap?.let { " (attemptCap=$it, runAttemptCount=$runAttemptCount)" } ?: "")
                    )

                    // Honor attemptCap as a hard ceiling. runAttemptCount is 0-based, so
                    // attemptCap=3 means we accept runs 0, 1, 2 — retry only if (runAttemptCount + 1) < cap.
                    // Precedence mirrors iOS handleOneTimeResult: the per-result attemptCap wins;
                    // otherwise fall back to Constraints.maxRetries (N retries → cap of N+1 total
                    // runs) stamped into inputData; otherwise uncapped (WorkManager quota governs).
                    val cap = result.attemptCap ?: run {
                        val maxRetries = inputData.getInt(NativeTaskScheduler.KEY_MAX_RETRIES, -1)
                        if (maxRetries >= 0) maxRetries + 1 else null
                    }
                    if (cap != null && runAttemptCount + 1 >= cap) {
                        Logger.w(
                            LogTags.WORKER,
                            "$workerLogTag retry cap reached for $workerClassName (cap=$cap, attempt=$runAttemptCount). Marking as permanent failure."
                        )
                        TaskEventManager.emit(
                            TaskCompletionEvent(
                                taskName = workerClassName,
                                success = false,
                                message = "Retry cap reached: ${result.reason}",
                                outputData = null
                            )
                        )
                        KmpWorkManagerRuntime.notifyTaskFailed(
                            TelemetryHook.TaskFailedEvent(
                                taskName = workerClassName,
                                platform = "android",
                                error = "Retry cap reached: ${result.reason}",
                                durationMs = duration,
                                retryCount = runAttemptCount
                            )
                        )
                        historyStatus = ExecutionStatus.ABANDONED
                        return Result.failure()
                    }

                    // Honor delayMs by passing through WorkManager's `Result.retry()` — the
                    // request-level backoff policy still drives the actual wait. We log the
                    // requested delay so consumers can correlate when WorkManager fires the
                    // retry vs what the worker asked for. Per-result custom backoff requires
                    // request-level configuration; emit it for diagnostics only.
                    KmpWorkManagerRuntime.notifyTaskFailed(
                        TelemetryHook.TaskFailedEvent(
                            taskName = workerClassName,
                            platform = "android",
                            error = "Retry requested: ${result.reason}",
                            durationMs = duration,
                            retryCount = runAttemptCount
                        )
                    )
                    historyStatus = ExecutionStatus.FAILURE
                    isRetrying = true
                    return Result.retry()
                }
            }
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            historyDuration = duration

            // Distinguish permanent failures (no retry) from transient failures (retry).
            // Conservative classification: only flag exception types that are ALWAYS
            // programming/config errors at the worker boundary. The cost of incorrectly
            // marking a transient failure as permanent is lost user data (the work never
            // runs again); the cost of incorrectly marking a permanent failure as transient
            // is wasted retry attempts (bounded by WorkRequest backoff + attemptCap).
            // Data loss is the worse failure mode → err on retry.
            //
            // NPE / IllegalArgumentException / NumberFormatException are deliberately
            // EXCLUDED — they are commonly thrown by third-party SDKs and JSON parsers
            // on transient null/empty server responses and should be retried.
            val isPermanentFailure = e is kotlinx.serialization.SerializationException  // Schema mismatch
                || e is ClassNotFoundException                                          // Worker class gone
                || e is java.lang.reflect.InvocationTargetException                     // Reflection wiring broken
                || e is InstantiationException                                          // Worker can't be constructed

            Logger.e(LogTags.WORKER, "$workerLogTag ${if (isPermanentFailure) "permanent failure" else "crashed"}: $workerClassName", e)

            val completionEvent = TaskCompletionEvent(
                taskName = workerClassName,
                success = false,
                message = "Exception: ${e.message}",
                outputData = null
            )

            TaskEventManager.emit(completionEvent)
            KmpWorkManagerRuntime.notifyTaskCompleted(
                TelemetryHook.TaskCompletedEvent(
                    taskName = workerClassName,
                    platform = "android",
                    success = false,
                    durationMs = duration,
                    errorMessage = e.message
                )
            )
            KmpWorkManagerRuntime.notifyTaskFailed(
                TelemetryHook.TaskFailedEvent(
                    taskName = workerClassName,
                    platform = "android",
                    error = e.message ?: "Unknown exception",
                    durationMs = duration,
                    retryCount = runAttemptCount
                )
            )
            historyStatus = if (isPermanentFailure) ExecutionStatus.ABANDONED else ExecutionStatus.FAILURE
            if (isPermanentFailure) {
                return Result.failure()
            } else {
                isRetrying = true
                return Result.retry()
            }
        } finally {
            val nowMs = System.currentTimeMillis()
            withContext(NonCancellable) {
                // WorkManager uses UUID as work ID — `id.toString().contains("periodic")` is
                // always false. Only the tags check is reliable.
                val isPeriodic = tags.contains("type-periodic")
                
                // Keep overflow file when:
                //   - isRetrying: we explicitly returned Result.retry()
                //   - isPeriodic: WorkManager will fire the next period
                //   - wasCancelled: OS preempted us (Doze, stop, constraints) and WorkManager
                //     will reschedule per the WorkRequest's backoff policy. Deleting here
                //     causes resolveInputJson() to return null on the rerun → user payload lost.
                // Zombie cleanup is the safety net for the rare case where reschedule never happens.
                if (!isRetrying && !isPeriodic && !wasCancelled) {
                    try {
                        overflowInputFile?.delete()
                    } catch (e: Exception) {
                        Logger.w(LogTags.WORKER, "$workerLogTag failed to delete overflow file: ${e.message}")
                    }
                }

                if (historyStatus != null) {
                    runCatching {
                        KmpWorkManagerRuntime.executionHistoryStore?.save(
                            ExecutionRecord(
                                id = java.util.UUID.randomUUID().toString(),
                                chainId = id.toString(),
                                status = historyStatus!!,
                                startedAtMs = startTime,
                                endedAtMs = nowMs,
                                durationMs = historyDuration,
                                totalSteps = 1,
                                completedSteps = if (historyStatus == ExecutionStatus.SUCCESS) 1 else 0,
                                failedStep = if (historyStatus != ExecutionStatus.SUCCESS) 0 else null,
                                errorMessage = null,
                                retryCount = runAttemptCount,
                                platform = "android",
                                workerClassNames = listOf(workerClassName)
                            )
                        )
                    }
                }
            }
        }
    }
}
