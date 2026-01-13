package io.kmp.taskmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags

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
        const val EXTRA_TASK_ID = "io.kmp.taskmanager.TASK_ID"
        const val EXTRA_WORKER_CLASS = "io.kmp.taskmanager.WORKER_CLASS"
        const val EXTRA_INPUT_JSON = "io.kmp.taskmanager.INPUT_JSON"

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
                    description = "Notifications for exact time alarms from KMP TaskManager"
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

        try {
            handleAlarm(context, taskId, workerClassName, inputJson)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error handling alarm for task '$taskId'", e)
        }
    }

    /**
     * Override this method to implement custom alarm handling logic.
     *
     * **Important Notes:**
     * - This runs in BroadcastReceiver context (10s limit)
     * - For long-running work, use WorkManager or foreground service
     * - Consider using [goAsync()] for async work within time limit
     *
     * @param context Application context
     * @param taskId Unique task identifier
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data
     */
    abstract fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?
    )
}
