package io.kmp.taskmanager.sample.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.kmp.taskmanager.sample.R
import io.kmp.taskmanager.sample.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.sample.background.domain.TaskEventBus
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.time.measureTime

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

        return try {
            println("🤖 Android: Starting HeavyProcessingWorker...")
            println("🤖 Android: 🔥 Starting heavy computation...")

            // Real heavy computation: Calculate prime numbers
            var primes: List<Int> = emptyList()
            val duration = measureTime {
                primes = calculatePrimes(10000)
            }

            println("🤖 Android: ✓ Calculated ${primes.size} prime numbers")
            println("🤖 Android: ⚡ Computation took ${duration.inWholeMilliseconds}ms")
            println("🤖 Android: 📊 First 10 primes: ${primes.take(10)}")
            println("🤖 Android: 📊 Last 10 primes: ${primes.takeLast(10)}")

            // Simulate some processing time
            println("🤖 Android: 💾 Saving results...")
            delay(2000)

            println("🤖 Android: 🎉 HeavyProcessingWorker finished successfully")

            // Emit completion event
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Heavy Processing",
                    success = true,
                    message = "✅ Calculated ${primes.size} primes in ${duration.inWholeMilliseconds}ms"
                )
            )

            Result.success()
        } catch (e: Exception) {
            println("🤖 Android: HeavyProcessingWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Heavy Processing",
                    success = false,
                    message = "❌ Task failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }

    private fun calculatePrimes(limit: Int): List<Int> {
        val primes = mutableListOf<Int>()
        for (num in 2..limit) {
            if (isPrime(num)) {
                primes.add(num)
            }
        }
        return primes
    }

    private fun isPrime(n: Int): Boolean {
        if (n < 2) return false
        if (n == 2) return true
        if (n % 2 == 0) return false

        val sqrtN = sqrt(n.toDouble()).toInt()
        for (i in 3..sqrtN step 2) {
            if (n % i == 0) return false
        }
        return true
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
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