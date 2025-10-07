package com.example.kmpworkmanagerv2.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.kmpworkmanagerv2.R

/**
 * A BroadcastReceiver to handle exact alarms triggered by AlarmManager.
 * Its primary job is to build and display a user-facing notification.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title")?: "Reminder"
        val message = intent.getStringExtra("message")?: "Scheduled event"
        val notificationId = intent.getIntExtra("notificationId", 0)

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
        println("🔔 Android AlarmReceiver: Fired for '$title'")
    }
}