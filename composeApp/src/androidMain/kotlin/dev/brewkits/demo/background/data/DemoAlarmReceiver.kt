package dev.brewkits.demo.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.brewkits.kmpworkmanager.sample.R
import dev.brewkits.kmpworkmanager.background.data.AlarmReceiver as BaseAlarmReceiver
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling exact alarms triggered by AlarmManager.
 *
 * This implementation demonstrates how to extend the library's abstract [BaseAlarmReceiver]
 * to show custom notifications and emit events back to the UI.
 */
class DemoAlarmReceiver : BaseAlarmReceiver() {

    companion object {
        private const val CHANNEL_ID = "demo_alarm_channel"
        private const val CHANNEL_NAME = "Demo Exact Alarms"
    }

    /**
     * Override handleAlarm to implement custom logic.
     * Note: This is called by BaseAlarmReceiver.onReceive().
     */
    override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult
    ) {
        val title = "Reminder: $taskId"
        val message = "Scheduled event for $workerClassName"
        val notificationId = taskId.hashCode()

        Logger.i(LogTags.ALARM, "Alarm triggered - Title: '$title', ID: $notificationId")

        try {
            // Ensure notification channel exists (Android 8.0+)
            createNotificationChannel(context)

            // Build and show notification
            showNotification(context, title, message, notificationId)

            // Emit completion event for UI updates
            emitCompletionEvent(title, message, pendingResult)

            Logger.i(LogTags.ALARM, "Alarm notification displayed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to display alarm notification", e)
            pendingResult.finish()
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
     * Emit task completion event to notify UI. [pendingResult] must be finished after emit.
     */
    private fun emitCompletionEvent(title: String, message: String, pendingResult: BroadcastReceiver.PendingResult) {
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
            } finally {
                // Always finish the pending result to release the receiver
                pendingResult.finish()
            }
        }
    }
}