package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Abstract BroadcastReceiver for handling exact alarms scheduled via AlarmManager.
 *
 * **Usage:**
 * 1. Extend this class in your app
 * 2. Override [handleAlarm] to implement your custom logic
 * 3. Register in AndroidManifest.xml:
 * ```xml
 * <receiver
 *     android:name=".MyAlarmReceiver"
 *     android:enabled="true"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * **Example:**
 * ```kotlin
 * class MyAlarmReceiver : AlarmReceiver() {
 *     override fun handleAlarm(
 *         context: Context,
 *         taskId: String,
 *         workerClassName: String,
 *         inputJson: String?,
 *         pendingResult: PendingResult
 *     ) {
 *         val factory = KoinContext.get().get<WorkerFactory>()
 *         CoroutineScope(Dispatchers.IO).launch {
 *             try {
 *                 // IMPORTANT: treat null as a hard failure — do NOT use worker?.doWork()
 *                 // which silently does nothing and leaves the task result unresolved.
 *                 val worker = factory.createWorker(workerClassName)
 *                     ?: run {
 *                         TaskEventBus.emit(TaskCompletionEvent(
 *                             taskName = workerClassName,
 *                             success = false,
 *                             message = "Worker not found: $workerClassName"
 *                         ))
 *                         return@launch
 *                     }
 *                 worker.doWork(inputJson)
 *             } finally {
 *                 pendingResult.finish() // Always release the BroadcastReceiver
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Moved to library (previously in composeApp only)
 */
abstract class AlarmReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Intent extra keys for alarm data.
         *
         * [EXTRA_INPUT_JSON] carries inline JSON (≤ 8 KB).
         * [EXTRA_INPUT_JSON_FILE] carries a file path for JSON that exceeds the inline
         * threshold. [NativeTaskScheduler] automatically selects the right key at scheduling
         * time; [onReceive] resolves the file and passes the full content to [handleAlarm].
         * Host app code never needs to read these keys directly.
         */
        const val EXTRA_TASK_ID = "dev.brewkits.kmpworkmanager.TASK_ID"
        const val EXTRA_WORKER_CLASS = "dev.brewkits.kmpworkmanager.WORKER_CLASS"
        const val EXTRA_INPUT_JSON = "dev.brewkits.kmpworkmanager.INPUT_JSON"
        const val EXTRA_INPUT_JSON_FILE = "dev.brewkits.kmpworkmanager.INPUT_JSON_FILE"

        /**
         * Notification channel ID for alarm notifications
         */
        const val NOTIFICATION_CHANNEL_ID = "kmp_alarm_channel"
        const val NOTIFICATION_CHANNEL_NAME = "KMP Task Alarms"

        /**
         * Creates notification channel for alarm notifications (Android 8.0+)
         * Call this in Application.onCreate() or before scheduling alarms
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for exact time alarms from KMP WorkManager"
                    enableVibration(true)
                    enableLights(true)
                }

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)

                Logger.d(LogTags.ALARM, "Created notification channel: $NOTIFICATION_CHANNEL_ID")
            }
        }
    }

    /**
     * Final onReceive - extracts alarm data and delegates to [handleAlarm]
     * Do NOT override this method - override [handleAlarm] instead
     *
     */
    final override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val workerClassName = intent.getStringExtra(EXTRA_WORKER_CLASS)

        if (taskId == null || workerClassName == null) {
            Logger.e(LogTags.ALARM, "Invalid alarm intent - missing taskId or workerClassName")
            return
        }

        // Resolve inputJson: prefer overflow file (large JSON) over inline extra.
        // NativeTaskScheduler writes the overflow file and puts its path in EXTRA_INPUT_JSON_FILE
        // when the JSON exceeds 8 KB, to avoid TransactionTooLargeException in the Binder layer.
        //
        // IMPORTANT: The overflow file is NOT deleted here. It is passed to handleAlarm() via
        // overflowFilePath so the worker can delete it in its finally block after the work
        // completes or fails permanently. Deleting before work completes would destroy the
        // input data if the process is killed mid-execution (no WorkManager retry for alarms).
        // If handleAlarm() never deletes the file, cleanupZombieInputFiles() will remove it
        // after ZOMBIE_FILE_MAX_AGE_MS (default 24 h).
        val overflowFilePath: String? = intent.getStringExtra(EXTRA_INPUT_JSON_FILE)
        val inputJson: String? = overflowFilePath?.let { filePath ->
            try {
                val file = java.io.File(filePath)
                val content = if (file.exists()) file.readText() else null
                if (content == null) Logger.w(LogTags.ALARM, "Alarm overflow file missing: $filePath")
                content
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Failed to read alarm overflow file: $filePath", e)
                null
            }
        } ?: intent.getStringExtra(EXTRA_INPUT_JSON)

        Logger.i(LogTags.ALARM, "Alarm received - Task: '$taskId', Worker: '$workerClassName'")

        // Alarm has fired — remove persisted metadata so AlarmBootReceiver does not
        // attempt to reschedule it on the next reboot.
        AlarmStore.remove(context, taskId)

        // before async work completes. The PendingResult keeps the receiver alive.
        val pendingResult = goAsync()

        try {
            handleAlarm(context, taskId, workerClassName, inputJson, pendingResult, overflowFilePath)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error handling alarm for task '$taskId'", e)
            // Ensure we finish even on exception to avoid leaking the PendingResult
            pendingResult.finish()
        }
    }

    /**
     * Override this method to implement custom alarm handling logic.
     *
     * **Important Notes:**
     * - The [pendingResult] parameter keeps the receiver alive for async operations
     * - You MUST call `pendingResult.finish()` when your work is complete
     * - BroadcastReceiver has a ~10 second time limit even with goAsync()
     * - If [overflowFilePath] is non-null, **delete the file in your `finally` block** after
     *   the work succeeds or fails permanently. The library does NOT delete it automatically,
     *   so that the file survives a crash and the data is not silently lost.
     *
     * **Example with coroutine:**
     * ```kotlin
     * override fun handleAlarm(
     *     context: Context,
     *     taskId: String,
     *     workerClassName: String,
     *     inputJson: String?,
     *     pendingResult: PendingResult,
     *     overflowFilePath: String?
     * ) {
     *     CoroutineScope(Dispatchers.IO).launch {
     *         try {
     *             val worker = workerFactory.createWorker(workerClassName)
     *             // Updated to match new Worker interface with environment
     *             worker?.doWork(inputJson, WorkerEnvironment())
     *         } finally {
     *             // Delete overflow file AFTER work completes (success or permanent failure).
     *             overflowFilePath?.let { java.io.File(it).delete() }
     *             pendingResult.finish()
     *         }
     *     }
     * }
     * ```
     *
     * If you forget to delete [overflowFilePath], `cleanupZombieInputFiles()` will remove it
     * automatically after 24 hours.
     *
     * @param context Application context
     * @param taskId Unique task identifier
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data (content of overflow file if one was used)
     * @param pendingResult PendingResult from goAsync() — MUST call finish() when done
     * @param overflowFilePath Path to the large-JSON overflow file, or null for inline JSON.
     *   Delete this file in your finally block after the work is done.
     */
    open fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult,
        overflowFilePath: String?
    ) {
        // Default: forward to the legacy 5-parameter override for backward compatibility.
        // New subclasses should override the 6-parameter version instead.
        handleAlarm(context, taskId, workerClassName, inputJson, pendingResult)
    }

    /**
     * Legacy override point — kept for backward compatibility.
     * Prefer overriding [handleAlarm] with `overflowFilePath` parameter instead.
     *
     * **Warning:** If this override is used, the overflow file is NOT deleted automatically.
     * `cleanupZombieInputFiles()` will clean it up after 24 h.
     */
    protected open fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult
    ) {
        // No-op default — subclasses that override only the 6-param version never reach here.
    }
}
