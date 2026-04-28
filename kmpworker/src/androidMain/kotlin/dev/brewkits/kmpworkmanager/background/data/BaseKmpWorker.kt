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
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
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
                    historyStatus = if (result.shouldRetry) ExecutionStatus.FAILURE else ExecutionStatus.ABANDONED
                    if (result.shouldRetry) {
                        isRetrying = true
                        return Result.retry()
                    } else {
                        return Result.failure()
                    }
                }
            }
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            historyDuration = duration

            // Distinguish permanent failures (no retry) from transient failures (retry).
            // Serialization/parsing errors and missing worker configs are programming errors that
            // will NEVER succeed on retry — retrying wastes battery and WorkManager quota.
            // Also catch NullPointerException, NumberFormatException, etc. from corrupted input.
            val isPermanentFailure = e is kotlinx.serialization.SerializationException
                || e is ClassNotFoundException
                || e is IllegalArgumentException
                || e is NullPointerException           // Corrupted/null input data
                || e is NumberFormatException          // Malformed numeric input
                || e is java.lang.reflect.InvocationTargetException  // Reflection failures
                || e is InstantiationException         // Worker class can't be instantiated

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
                
                // Always delete overflow file unless retrying or periodic.
                // Cancelled tasks should not retain their overflow file until the 24h zombie
                // cleanup — causes unnecessary disk usage when many tasks are cancelled.
                if (!isRetrying && !isPeriodic) {
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
