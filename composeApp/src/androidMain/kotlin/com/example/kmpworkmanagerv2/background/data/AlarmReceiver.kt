package com.example.kmpworkmanagerv2.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.kmpworkmanagerv2.R
import com.example.kmpworkmanagerv2.background.domain.TaskCompletionEvent
import com.example.kmpworkmanagerv2.background.domain.TaskEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A BroadcastReceiver to handle exact alarms triggered by AlarmManager.
 * Its primary job is to build and display a user-facing notification.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title")?: "Reminder"
        val message = intent.getStringExtra("message")?: "Scheduled event"
        val notificationId = intent.getIntExtra("notificationId", 0)

        println("🔔 Android AlarmReceiver: Alarm triggered for '$title'")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "alarm_channel"
        val channel = NotificationChannel(channelId, "Alarms", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure you have this drawable
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(notificationId, notification)
        println("🔔 Android AlarmReceiver: Notification shown for '$title'")

        // Emit completion event to show toast in UI
        CoroutineScope(Dispatchers.Main).launch {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = title,
                    success = true,
                    message = "⏰ Alarm triggered: $message"
                )
            )
            println("🔔 Android AlarmReceiver: Event emitted for '$title'")
        }
    }
}