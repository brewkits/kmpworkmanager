package com.example.kmpworkmanagerv2.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.kmpworkmanagerv2.R
import kotlinx.datetime.Clock
import kotlin.time.ExperimentalTime

/**
 * A generic CoroutineWorker that acts as the entry point for all deferrable tasks.
 * It now also shows a notification to provide visible feedback that the task has run.
 */
class KmpWorker(
    private val appContext: Context, // Changed to private val to be accessible in the class
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    @OptIn(ExperimentalTime::class)
    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName")?: return Result.failure()

        // Determine the title for the notification based on the worker type
        val notificationTitle = when (workerClassName) {
            WorkerTypes.SYNC_WORKER -> {
                println("🤖 Android KmpWorker: Executing SYNC_WORKER...")
                "Periodic Sync Task"
            }
            WorkerTypes.UPLOAD_WORKER -> {
                println("🤖 Android KmpWorker: Executing UPLOAD_WORKER...")
                "Upload Task"
            }
            else -> "Background Task"
        }

        // --- START: Added notification action ---
        // Show a notification to visually confirm the worker has executed.
        showNotification(
            context = appContext,
            title = notificationTitle,
            message = "Task executed at ${kotlin.time.Clock.System.now()}"
        )
        // --- END: Added notification action ---

        return Result.success()
    }

    /**
     * Helper function to display a notification.
     * This makes testing and debugging much easier as you get a visual confirmation.
     */
    private fun showNotification(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "workmanager_channel"
        val channel = NotificationChannel(channelId, "WorkManager Tasks", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Use a unique ID for each notification to avoid overwriting
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}