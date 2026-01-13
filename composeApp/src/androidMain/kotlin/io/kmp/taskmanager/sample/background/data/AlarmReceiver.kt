package io.kmp.taskmanager.sample.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.kmp.taskmanager.sample.R
import io.kmp.taskmanager.sample.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.sample.background.domain.TaskEventBus
import io.kmp.taskmanager.sample.utils.Logger
import io.kmp.taskmanager.sample.utils.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling exact alarms triggered by AlarmManager.
 *
 * Responsibilities:
 * - Create notification channel (Android 8.0+)
 * - Display high-priority notification to user
 * - Emit task completion event for UI updates
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "alarm_channel"
        private const val CHANNEL_NAME = "Exact Alarms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Reminder"
        val message = intent.getStringExtra("message") ?: "Scheduled event"
        val notificationId = intent.getIntExtra("notificationId", 0)

        Logger.i(LogTags.ALARM, "Alarm triggered - Title: '$title', ID: $notificationId")

        try {
            // Ensure notification channel exists (Android 8.0+)
            createNotificationChannel(context)

            // Build and show notification
            showNotification(context, title, message, notificationId)

            // Emit completion event for UI updates
            emitCompletionEvent(title, message)

            Logger.i(LogTags.ALARM, "Alarm notification displayed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to display alarm notification", e)
        }
    }

    /**
     * Create notification channel for alarms (required on Android 8.0+)
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Check if channel already exists
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
                Logger.d(LogTags.ALARM, "Notification channel already exists")
                return
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for exact time alarms and reminders"
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(channel)
            Logger.d(LogTags.ALARM, "Notification channel created: $CHANNEL_ID")
        } else {
            Logger.d(LogTags.ALARM, "Notification channel not required (API < 26)")
        }
    }

    /**
     * Build and display the alarm notification
     */
    private fun showNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(notificationId, notification)
        Logger.d(LogTags.ALARM, "Notification posted with ID: $notificationId")
    }

    /**
     * Emit task completion event to notify UI
     */
    private fun emitCompletionEvent(title: String, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = title,
                        success = true,
                        message = "⏰ Alarm triggered: $message"
                    )
                )
                Logger.d(LogTags.ALARM, "Task completion event emitted")
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Failed to emit completion event", e)
            }
        }
    }
}