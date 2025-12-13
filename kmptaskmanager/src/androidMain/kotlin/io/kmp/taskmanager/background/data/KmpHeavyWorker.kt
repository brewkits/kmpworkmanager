package io.kmp.taskmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags

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
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "kmp_heavy_worker_channel"
        const val CHANNEL_NAME = "KMP Heavy Tasks"
        const val NOTIFICATION_ID = 1001

        /**
         * Worker data keys
         */
        const val WORKER_CLASS_KEY = "workerClassName"
        const val INPUT_JSON_KEY = "inputJson"
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
     */
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Background Task Running")
            .setContentText("Processing heavy task...")
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
                description = "Notifications for long-running background tasks from KMP TaskManager"
                setShowBadge(false) // Don't show badge on app icon
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Executes the actual heavy work by delegating to the specified worker class.
     *
     * **Note:** This is a simplified implementation. For production use:
     * - Integrate with your DI framework (Koin, Hilt, etc.)
     * - Use reflection or a worker factory registry
     * - Handle worker instantiation errors gracefully
     *
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data
     * @return true if work succeeded, false otherwise
     */
    private suspend fun executeHeavyWork(workerClassName: String, inputJson: String?): Boolean {
        // TODO: Integrate with your worker factory/DI system
        // Example with Koin:
        // val workerFactory: WorkerFactory = KoinContext.get().get()
        // val worker = workerFactory.createWorker(workerClassName) ?: return false
        // return worker.doWork(inputJson)

        Logger.w(LogTags.WORKER, """
            KmpHeavyWorker.executeHeavyWork() is not yet integrated with worker factory.

            To use heavy workers:
            1. Extend KmpHeavyWorker in your app
            2. Override executeHeavyWork() to integrate with your DI/factory
            3. Use your extended worker class in WorkManager configuration

            Worker: $workerClassName
        """.trimIndent())

        // Placeholder: return false (user must implement)
        return false
    }
}
