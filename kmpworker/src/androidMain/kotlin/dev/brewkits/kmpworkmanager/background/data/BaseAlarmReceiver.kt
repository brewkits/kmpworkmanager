package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Convenience base class that wraps [AlarmReceiver] with automatic lifecycle management.
 *
 * Extend this instead of [AlarmReceiver] when you want the library to handle:
 * - Overflow file cleanup (`kmp_input_*.json`) after work completes
 * - `pendingResult.finish()` in the `finally` block
 * - Coroutine dispatch to [Dispatchers.IO]
 * - **Bounded execution** via [workTimeoutMs] so a hung worker cannot leak past the
 *   BroadcastReceiver budget and leave a dangling scope behind.
 *
 * You only need to override [doAlarmWork] with your business logic.
 *
 * **Example:**
 * ```kotlin
 * class MySyncReceiver : BaseAlarmReceiver() {
 *     override suspend fun doAlarmWork(
 *         context: Context,
 *         taskId: String,
 *         workerClassName: String,
 *         inputJson: String?
 *     ) {
 *         val factory = KoinContext.get().get<AndroidWorkerFactory>()
 *         val worker = factory.createWorker(workerClassName)
 *             ?: error("Worker not found: $workerClassName")
 *         worker.doWork(inputJson)
 *     }
 * }
 * ```
 *
 * **Thread safety:** [doAlarmWork] runs on [Dispatchers.IO]. Do not call UI operations
 * from it. All suspend functions are safe to call from [doAlarmWork].
 *
 * **Error handling:** Uncaught exceptions from [doAlarmWork] are logged and swallowed.
 * Emit a [dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent] explicitly
 * if your caller needs to observe failures.
 */
abstract class BaseAlarmReceiver : AlarmReceiver() {

    /**
     * Hard ceiling for [doAlarmWork]. Default `8_000` ms — slightly below the BroadcastReceiver
     * budget (~10 s even with `goAsync()`) so we cancel cleanly before the OS does it for us.
     * Override to tighten further; raising it past ~9 s is unsafe and can leak the
     * receiver / corrupt PendingResult state.
     */
    protected open val workTimeoutMs: Long = DEFAULT_WORK_TIMEOUT_MS

    final override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult,
        overflowFilePath: String?
    ) {
        runHandleAlarmScope(
            context = context,
            taskId = taskId,
            workerClassName = workerClassName,
            inputJson = inputJson,
            overflowFilePath = overflowFilePath,
            onFinish = {
                // Always release the BroadcastReceiver. Catch defensively — `finish()` may
                // throw IllegalStateException if the receiver already completed.
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    Logger.w(LogTags.ALARM, "pendingResult.finish() failed for '$taskId'", e)
                }
            }
        )
    }

    /**
     * The actual launch/timeout/cleanup body, broken out so unit tests can exercise it
     * without needing a real `BroadcastReceiver.PendingResult` (which is a protected
     * inner class and cannot be mocked from outside the framework).
     *
     * Per-invocation `SupervisorJob` scope so we can cancel the coroutine deterministically
     * in `finally`. The pre-v2.5 unstructured `CoroutineScope(Dispatchers.IO).launch`
     * leaked work past the receiver lifetime — the OS could kill the receiver process
     * while the coroutine kept running, leaving overflow files orphaned and the
     * `PendingResult` in an undefined state.
     */
    internal fun runHandleAlarmScope(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        overflowFilePath: String?,
        onFinish: () -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(workTimeoutMs) {
                    doAlarmWork(context, taskId, workerClassName, inputJson)
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w(
                    LogTags.ALARM,
                    "doAlarmWork for task '$taskId' exceeded ${workTimeoutMs}ms budget — cancelling " +
                        "to honor the BroadcastReceiver lifetime. Work may be incomplete; the alarm " +
                        "metadata is preserved so the next schedule can retry."
                )
            } catch (e: CancellationException) {
                // Propagate; do not swallow structured cancellation.
                throw e
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "BaseAlarmReceiver: doAlarmWork failed for task '$taskId'", e)
            } finally {
                overflowFilePath?.let { path ->
                    val deleted = try {
                        java.io.File(path).delete()
                    } catch (e: Exception) {
                        Logger.w(LogTags.ALARM, "Failed to delete overflow file for '$taskId': $path", e)
                        false
                    }
                    if (deleted) {
                        Logger.d(LogTags.ALARM, "Deleted overflow file for task '$taskId': $path")
                    }
                }
                onFinish()
                // Tear down the scope so any straggler children (e.g. if doAlarmWork spawned
                // unstructured launches) are cancelled rather than left running detached.
                scope.cancel("BaseAlarmReceiver: handleAlarm done for '$taskId'")
            }
        }
    }

    /**
     * Implement your alarm handling logic here.
     *
     * Called on [Dispatchers.IO] with a [workTimeoutMs] timeout. The [PendingResult] lifecycle
     * and overflow file cleanup are managed automatically by [BaseAlarmReceiver] — do NOT call
     * `pendingResult.finish()` or `file.delete()` yourself.
     *
     * @param context Application context
     * @param taskId Unique task identifier from the scheduled alarm
     * @param workerClassName Worker class name from the scheduled alarm
     * @param inputJson Resolved JSON input (from inline extra or overflow file)
     */
    abstract suspend fun doAlarmWork(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    )

    companion object {
        /** Default ceiling: 8 s. The OS will hard-kill the receiver at ~10 s. */
        const val DEFAULT_WORK_TIMEOUT_MS: Long = 8_000L
    }
}
