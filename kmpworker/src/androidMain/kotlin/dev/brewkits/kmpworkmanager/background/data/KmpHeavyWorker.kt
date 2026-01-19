package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Heavy worker that runs in foreground service with persistent notification.
 * Used for long-running tasks (>10 minutes) or CPU-intensive work.
 *
 * **When to use:**
 * - CPU-intensive tasks (video processing, encryption, large file operations)
 * - Tasks that may take > 10 minutes
 * - Tasks that should not be interrupted by system doze mode
 *
 * **How to use:**
 * Set `Constraints(isHeavyTask = true)` when scheduling:
 * ```kotlin
 * scheduler.enqueue(
 *     id = "heavy-processing",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "ProcessVideoWorker",
 *     constraints = Constraints(isHeavyTask = true) // ← Use KmpHeavyWorker
 * )
 * ```
 *
 * **v2.0.1+: Customize notification text (for localization):**
 * ```kotlin
 * val inputData = buildJsonObject {
 *     put(KmpHeavyWorker.NOTIFICATION_TITLE_KEY, "處理中")
 *     put(KmpHeavyWorker.NOTIFICATION_TEXT_KEY, "正在處理大型任務...")
 * }.toString()
 *
 * scheduler.enqueue(
 *     id = "heavy-processing",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "ProcessVideoWorker",
 *     constraints = Constraints(isHeavyTask = true),
 *     inputJson = inputData  // ← Custom notification text
 * )
 * ```
 *
 * **Requirements:**
 * - Requires `FOREGROUND_SERVICE` permission in AndroidManifest.xml
 * - Shows persistent notification while running (Android requirement)
 * - Notification cannot be dismissed until task completes
 *
 * **AndroidManifest.xml:**
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 * ```
 *
 * **v3.0.0+**: Moved to library (previously in composeApp only)
 */
class KmpHeavyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val workerFactory: AndroidWorkerFactory by inject()

    companion object {
        const val CHANNEL_ID = "kmp_heavy_worker_channel"
        const val CHANNEL_NAME = "KMP Heavy Tasks"
        const val NOTIFICATION_ID = 1001

        /**
         * Worker data keys
         */
        const val WORKER_CLASS_KEY = "workerClassName"
        const val INPUT_JSON_KEY = "inputJson"

        /**
         * v2.0.1+: Notification customization keys
         * These can be passed via inputData to customize the foreground notification
         */
        const val NOTIFICATION_TITLE_KEY = "notificationTitle"
        const val NOTIFICATION_TEXT_KEY = "notificationText"

        /**
         * Default notification text (used if not provided via inputData)
         */
        private const val DEFAULT_NOTIFICATION_TITLE = "Background Task Running"
        private const val DEFAULT_NOTIFICATION_TEXT = "Processing heavy task..."
    }

    override suspend fun doWork(): Result {
        Logger.i(LogTags.WORKER, "KmpHeavyWorker starting foreground service")

        // 1. Start foreground service with notification
        setForeground(createForegroundInfo())

        // 2. Get worker class name and input
        val workerClassName = inputData.getString(WORKER_CLASS_KEY)
        val inputJson = inputData.getString(INPUT_JSON_KEY)

        if (workerClassName == null) {
            Logger.e(LogTags.WORKER, "KmpHeavyWorker missing workerClassName")
            return Result.failure()
        }

        Logger.i(LogTags.WORKER, "Executing heavy worker: $workerClassName")

        // 3. Execute the actual worker
        return try {
            val success = executeHeavyWork(workerClassName, inputJson)

            if (success) {
                Logger.i(LogTags.WORKER, "KmpHeavyWorker completed successfully: $workerClassName")
                Result.success()
            } else {
                Logger.w(LogTags.WORKER, "KmpHeavyWorker returned failure: $workerClassName")
                Result.failure()
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "KmpHeavyWorker exception: $workerClassName", e)
            Result.failure()
        }
    }

    /**
     * Creates foreground notification info
     * v2.0.1+: Now supports custom notification text via inputData
     */
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        // v2.0.1+: Allow custom notification text for localization
        val notificationTitle = inputData.getString(NOTIFICATION_TITLE_KEY) ?: DEFAULT_NOTIFICATION_TITLE
        val notificationText = inputData.getString(NOTIFICATION_TEXT_KEY) ?: DEFAULT_NOTIFICATION_TEXT

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true) // Cannot be dismissed
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for less intrusion
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /**
     * Creates notification channel for foreground service (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance for background work
            ).apply {
                description = "Notifications for long-running background tasks from KMP WorkManager"
                setShowBadge(false) // Don't show badge on app icon
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Executes the actual heavy work by delegating to the specified worker class.
     *
     * v1.0.0+: Now uses AndroidWorkerFactory from Koin
     *
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data
     * @return true if work succeeded, false otherwise
     */
    private suspend fun executeHeavyWork(workerClassName: String, inputJson: String?): Boolean {
        val worker = workerFactory.createWorker(workerClassName)

        if (worker == null) {
            Logger.e(LogTags.WORKER, "Worker factory returned null for heavy worker: $workerClassName")
            return false
        }

        return worker.doWork(inputJson)
    }
}
