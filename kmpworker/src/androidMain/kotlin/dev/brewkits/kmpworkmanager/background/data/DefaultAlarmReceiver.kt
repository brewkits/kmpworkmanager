package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import dev.brewkits.kmpworkmanager.KmpWorkManagerAndroid
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Default implementation of [AlarmReceiver] used when the host app does not register a
 * custom `<receiver>` for `TaskTrigger.Exact`'s `ATTEMPT_BACKGROUND_RUN`-equivalent path.
 *
 * **Fixed bug**: prior to this fix, this class only logged the alarm and finished the
 * `PendingResult` without ever invoking the scheduled worker — every `TaskTrigger.Exact`
 * task scheduled through the public `KmpWorkManager.initialize()` path (which always wires
 * `NativeTaskScheduler` to this receiver; there was no way for a host app to substitute its
 * own via public API) fired its `AlarmManager` alarm on time but never actually ran the work.
 *
 * Extends [BaseAlarmReceiver] to get overflow-file cleanup, bounded execution
 * ([BaseAlarmReceiver.workTimeoutMs]), and `pendingResult.finish()` handling for free — the
 * only thing this class adds is resolving and invoking the worker, exactly the recipe
 * [BaseAlarmReceiver]'s own KDoc documents for a host-authored subclass.
 */
internal class DefaultAlarmReceiver : BaseAlarmReceiver() {

    override suspend fun doAlarmWork(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    ) {
        val startTime = System.currentTimeMillis()

        val factory = try {
            KmpWorkManagerAndroid.requireRegistry().androidWorkerFactory
        } catch (e: IllegalStateException) {
            Logger.e(
                LogTags.ALARM,
                "Cannot execute exact-alarm task '$taskId' ($workerClassName): " +
                    "KmpWorkManager is not initialized in this process. Call " +
                    "KmpWorkManager.initialize() in Application.onCreate().",
                e
            )
            emitFailure(taskId, workerClassName, "KmpWorkManager not initialized")
            return
        }

        val worker = try {
            factory.createWorker(workerClassName)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to create worker '$workerClassName' for exact alarm '$taskId'", e)
            emitFailure(taskId, workerClassName, "Worker creation failed: ${e.message}")
            return
        }

        if (worker == null) {
            Logger.e(LogTags.ALARM, "Worker not found for exact alarm '$taskId': $workerClassName")
            emitFailure(taskId, workerClassName, "Worker not found: $workerClassName")
            return
        }

        Logger.i(LogTags.ALARM, "Executing exact-alarm task '$taskId' — worker: $workerClassName")

        val env = WorkerEnvironment(progressListener = null, isCancelled = { false })
        val result = try {
            worker.doWork(inputJson, env)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Worker '$workerClassName' threw for exact alarm '$taskId'", e)
            emitFailure(taskId, workerClassName, "Worker threw: ${e.message}")
            return
        } finally {
            worker.close()
        }

        val duration = System.currentTimeMillis() - startTime
        when (result) {
            is WorkerResult.Success -> {
                Logger.i(LogTags.ALARM, "Exact-alarm task '$taskId' ($workerClassName) succeeded in ${duration}ms")
                TaskEventManager.emit(
                    TaskCompletionEvent(
                        taskName = workerClassName,
                        success = true,
                        message = result.message ?: "Worker completed successfully",
                        outputData = result.data
                    )
                )
                KmpWorkManagerRuntime.notifyTaskCompleted(
                    TelemetryHook.TaskCompletedEvent(
                        taskName = workerClassName,
                        chainId = null,
                        stepIndex = null,
                        platform = "android",
                        success = true,
                        durationMs = duration
                    )
                )
            }
            is WorkerResult.Failure -> {
                Logger.w(LogTags.ALARM, "Exact-alarm task '$taskId' ($workerClassName) failed: ${result.message}")
                emitFailure(taskId, workerClassName, result.message, durationMs = duration)
            }
            is WorkerResult.Retry -> {
                // Exact alarms are one-shot AlarmManager broadcasts — there is no scheduler-level
                // retry/backoff mechanism to hand this off to (unlike WorkManager-backed workers).
                // Surface it as a failure rather than silently dropping the retry request.
                Logger.w(
                    LogTags.ALARM,
                    "Exact-alarm task '$taskId' ($workerClassName) requested a retry " +
                        "(${result.reason}), but exact alarms do not support retry/backoff — treating as failure."
                )
                emitFailure(taskId, workerClassName, "Retry requested but not supported for exact alarms: ${result.reason}", durationMs = duration)
            }
        }
    }

    private suspend fun emitFailure(taskId: String, workerClassName: String, message: String?, durationMs: Long = 0L) {
        val errorMessage = message ?: "Unknown failure"
        TaskEventManager.emit(
            TaskCompletionEvent(
                taskName = workerClassName,
                success = false,
                message = errorMessage,
                outputData = null
            )
        )
        KmpWorkManagerRuntime.notifyTaskCompleted(
            TelemetryHook.TaskCompletedEvent(
                taskName = workerClassName,
                chainId = null,
                stepIndex = null,
                platform = "android",
                success = false,
                durationMs = durationMs,
                errorMessage = errorMessage
            )
        )
        KmpWorkManagerRuntime.notifyTaskFailed(
            TelemetryHook.TaskFailedEvent(
                taskName = workerClassName,
                chainId = null,
                stepIndex = null,
                platform = "android",
                error = errorMessage,
                durationMs = durationMs,
                retryCount = 0
            )
        )
    }
}
