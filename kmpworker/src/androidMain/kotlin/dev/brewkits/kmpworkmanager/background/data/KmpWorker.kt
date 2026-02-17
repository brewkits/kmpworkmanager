package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.R
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger

/**
 * A generic CoroutineWorker that delegates to user-provided AndroidWorker implementations.
 *
 * v4.0.0+: Uses AndroidWorkerFactory from Koin instead of hardcoded when() statement
 *
 * This worker acts as the entry point for all deferrable tasks and:
 * - Retrieves the worker class name from input data
 * - Uses the injected AndroidWorkerFactory to create the worker instance
 * - Delegates execution to the worker's doWork() method
 * - Emits events to TaskEventBus for UI updates
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val workerFactory: AndroidWorkerFactory = KmpWorkManagerKoin.getKoin().get()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kmp_worker_tasks"
        private const val NOTIFICATION_ID = 0x4B4D5000.toInt()
    }

    /**
     * Required override for WorkManager 2.10.0+.
     *
     * WorkManager 2.10.0+ calls getForegroundInfoAsync() in the worker execution path
     * even for non-foreground workers. Without this override, the default CoroutineWorker
     * implementation throws IllegalStateException: "Not implemented".
     *
     * KmpWorker does not run as a foreground service — the notification provided here
     * serves as a fallback and will only be shown if WorkManager explicitly promotes
     * the task to a foreground service (e.g. on low-memory devices or API 31+).
     *
     * **Localization:** Notification strings are resolved from Android string resources.
     * Override `kmp_worker_notification_title` and `kmp_worker_notification_channel_name`
     * in your app's `res/values-xx/strings.xml` to support multiple languages.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val title = applicationContext.getString(R.string.kmp_worker_notification_title)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channelName = applicationContext.getString(R.string.kmp_worker_notification_channel_name)
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        channelName,
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()
        val inputJson = inputData.getString("inputJson")

        Logger.i(LogTags.WORKER, "KmpWorker executing: $workerClassName")

        return try {
            val worker = workerFactory.createWorker(workerClassName)

            if (worker == null) {
                Logger.e(LogTags.WORKER, "Worker factory returned null for: $workerClassName")
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = workerClassName,
                        success = false,
                        message = "❌ Worker not found: $workerClassName"
                    )
                )
                return Result.failure()
            }

            val result = worker.doWork(inputJson)

            when (result) {
                is WorkerResult.Success -> {
                    val message = result.message ?: "Worker completed successfully"
                    Logger.i(LogTags.WORKER, "Worker success: $workerClassName - $message")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = true,
                            message = message,
                            outputData = result.data
                        )
                    )
                    Result.success()
                }
                is WorkerResult.Failure -> {
                    Logger.w(LogTags.WORKER, "Worker failure: $workerClassName - ${result.message}")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = false,
                            message = result.message,
                            outputData = null
                        )
                    )

                    if (result.shouldRetry) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Worker execution failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName,
                    success = false,
                    message = "❌ Failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }
}