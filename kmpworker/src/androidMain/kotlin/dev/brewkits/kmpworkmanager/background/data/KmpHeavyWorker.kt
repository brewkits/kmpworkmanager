package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Android implementation for "heavy" tasks that requires a foreground service.
 */
class KmpHeavyWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    workerFactory: AndroidWorkerFactory
) : BaseKmpWorker(appContext, workerParams, workerFactory) {

    @Deprecated("Use constructor with workerFactory")
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        KmpWorkManagerKoin.getKoin().get()
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kmp_heavy_worker_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override val workerLogTag: String get() = "KmpHeavyWorker"

    override suspend fun doWork(): Result {
        // Set foreground service info before starting work
        setForeground(createForegroundInfo())
        return doWorkInternal()
    }

    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)
            ?: run {
                Logger.e(LogTags.WORKER, "Worker not found: $workerClassName")
                return WorkerResult.Failure("Worker not found: $workerClassName")
            }

        val env = WorkerEnvironment(
            progressListener = object : ProgressListener {
                override fun onProgressUpdate(progress: WorkerProgress) {
                    setProgressAsync(androidx.work.Data.Builder().putInt("progress", progress.progress).build())
                }
            },
            isCancelled = { isStopped }
        )

        return worker.doWork(inputJson, env)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val title = "Background Task Running"
        val content = "A background operation is in progress."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Background Workers"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
