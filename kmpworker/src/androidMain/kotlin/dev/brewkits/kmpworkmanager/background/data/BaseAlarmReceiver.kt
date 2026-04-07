package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Convenience base class that wraps [AlarmReceiver] with automatic lifecycle management.
 *
 * Extend this instead of [AlarmReceiver] when you want the library to handle:
 * - Overflow file cleanup (`kmp_input_*.json`) after work completes
 * - `pendingResult.finish()` in the `finally` block
 * - Coroutine dispatch to [Dispatchers.IO]
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

    final override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult,
        overflowFilePath: String?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                doAlarmWork(context, taskId, workerClassName, inputJson)
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "BaseAlarmReceiver: doAlarmWork failed for task '$taskId'", e)
            } finally {
                // Delete overflow file after work completes — success or failure.
                // This is the "black box" cleanup that library consumers should not need to
                // worry about when using BaseAlarmReceiver.
                overflowFilePath?.let { path ->
                    val deleted = java.io.File(path).delete()
                    if (deleted) {
                        Logger.d(LogTags.ALARM, "Deleted overflow file for task '$taskId': $path")
                    }
                }
                pendingResult.finish()
            }
        }
    }

    /**
     * Implement your alarm handling logic here.
     *
     * Called on [Dispatchers.IO]. The [pendingResult] lifecycle and overflow file cleanup
     * are managed automatically by [BaseAlarmReceiver] — do NOT call `pendingResult.finish()`
     * or `file.delete()` yourself.
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
}
