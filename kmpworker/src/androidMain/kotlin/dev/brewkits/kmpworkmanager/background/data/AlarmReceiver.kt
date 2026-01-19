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
 *         inputJson: String?
 *     ) {
 *         // Get worker factory from Koin
 *         val factory = KoinContext.get().get<WorkerFactory>()
 *         val worker = factory.createWorker(workerClassName)
 *
 *         // Execute work (consider using WorkManager for reliability)
 *         CoroutineScope(Dispatchers.IO).launch {
 *             worker?.doWork(inputJson)
 *         }
 *     }
 * }
 * ```
 *
 * **v3.0.0+**: Moved to library (previously in composeApp only)
 */
abstract class AlarmReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Intent extra keys for alarm data
         */
        const val EXTRA_TASK_ID = "dev.brewkits.kmpworkmanager.TASK_ID"
        const val EXTRA_WORKER_CLASS = "dev.brewkits.kmpworkmanager.WORKER_CLASS"
        const val EXTRA_INPUT_JSON = "dev.brewkits.kmpworkmanager.INPUT_JSON"

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
     * v2.0.1+: Added goAsync() support to prevent process kill during async operations
     */
    final override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val workerClassName = intent.getStringExtra(EXTRA_WORKER_CLASS)
        val inputJson = intent.getStringExtra(EXTRA_INPUT_JSON)

        if (taskId == null || workerClassName == null) {
            Logger.e(LogTags.ALARM, "Invalid alarm intent - missing taskId or workerClassName")
            return
        }

        Logger.i(LogTags.ALARM, "Alarm received - Task: '$taskId', Worker: '$workerClassName'")

        // v2.0.1+: Use goAsync() to prevent Android from killing the process
        // before async work completes. The PendingResult keeps the receiver alive.
        val pendingResult = goAsync()

        try {
            handleAlarm(context, taskId, workerClassName, inputJson, pendingResult)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error handling alarm for task '$taskId'", e)
            // Ensure we finish even on exception to avoid leaking the PendingResult
            pendingResult.finish()
        }
    }

    /**
     * Override this method to implement custom alarm handling logic.
     *
     * **Important Notes (v2.0.1+):**
     * - The [pendingResult] parameter keeps the receiver alive for async operations
     * - You MUST call `pendingResult.finish()` when your work is complete
     * - Recommended: Use WorkManager for reliable long-running work
     * - BroadcastReceiver has a ~10 second time limit even with goAsync()
     *
     * **Example with coroutine:**
     * ```kotlin
     * override fun handleAlarm(
     *     context: Context,
     *     taskId: String,
     *     workerClassName: String,
     *     inputJson: String?,
     *     pendingResult: PendingResult
     * ) {
     *     CoroutineScope(Dispatchers.IO).launch {
     *         try {
     *             // Do work here
     *             val worker = workerFactory.createWorker(workerClassName)
     *             worker?.doWork(inputJson)
     *         } finally {
     *             // CRITICAL: Always call finish() in finally block
     *             pendingResult.finish()
     *         }
     *     }
     * }
     * ```
     *
     * **Best Practice:** For reliability, just schedule a WorkManager job from here:
     * ```kotlin
     * override fun handleAlarm(..., pendingResult: PendingResult) {
     *     // Schedule expedited work
     *     val workRequest = OneTimeWorkRequestBuilder<MyWorker>()
     *         .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
     *         .build()
     *     WorkManager.getInstance(context).enqueue(workRequest)
     *     pendingResult.finish()
     * }
     * ```
     *
     * @param context Application context
     * @param taskId Unique task identifier
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data
     * @param pendingResult PendingResult from goAsync() - MUST call finish() when done
     */
    abstract fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult
    )
}
