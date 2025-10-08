package com.example.kmpworkmanagerv2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.koin.mp.KoinPlatform.getKoin

actual fun showNotification(title: String, body: String) {
    val context: Context = getKoin().get()
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "test_notification_channel"
    val channel = NotificationChannel(channelId, "Test Notifications", NotificationManager.IMPORTANCE_DEFAULT)
    notificationManager.createNotificationChannel(channel)

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(body)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
}
