package com.example.kmpworkmanagerv2.background.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.kmpworkmanagerv2.R
import kotlinx.coroutines.delay

class KmpHeavyWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val notificationTitle = "Heavy Task Running"
        val initialMessage = "Starting heavy processing..."

        // BẮT BUỘC: Hiển thị notification và chuyển worker thành Foreground Service
        setForeground(createForegroundInfo(notificationTitle, initialMessage))

        // Giả lập công việc nặng đang chạy
        println("🤖 Android KmpHeavyWorker: Starting heavy work...")
        delay(30_000) // Giả lập chạy trong 30 giây
        println("🤖 Android KmpHeavyWorker: Heavy work finished.")

        // (Tùy chọn) Cập nhật notification khi hoàn thành
        showCompletionNotification(notificationTitle, "Processing finished successfully.")

        return Result.success()
    }

    private fun createForegroundInfo(title: String, message: String): ForegroundInfo {
        val channelId = "heavy_task_channel"
        val notificationId = System.currentTimeMillis().toInt()

        createNotificationChannel(channelId, "Heavy Tasks")

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true) // Quan trọng: làm cho notification không thể bị xóa
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    // Hàm hiển thị notification khi hoàn tất (không còn là foreground)
    private fun showCompletionNotification(title: String, message: String) {
        val channelId = "heavy_task_channel"
        val notificationId = System.currentTimeMillis().toInt()
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}