package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.R
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Standard implementation of [BaseKmpWorker] for non-heavy background tasks.
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    workerFactory: AndroidWorkerFactory
) : BaseKmpWorker(appContext, workerParams, workerFactory) {

    @Deprecated(
        "Use the constructor that accepts a workerFactory parameter for proper DI support.",
        level = DeprecationLevel.WARNING
    )
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        KmpWorkManagerKoin.getKoin().get()
    )

    override val workerLogTag: String get() = "KmpWorker"

    override suspend fun doWork(): Result = doWorkInternal()

    /**
     * Required override for WorkManager 2.10.0+ and for expedited work on API < 31.
     *
     * The scheduler marks immediate non-heavy tasks `setExpedited(...)`. On API < 31 an
     * expedited request runs as a foreground service, so WorkManager calls
     * [getForegroundInfo]. Without this override the default [androidx.work.CoroutineWorker]
     * implementation throws `IllegalStateException("Not implemented")` and the worker crashes.
     *
     * [KmpWorker] is not a foreground worker by design — this notification is a fallback shown
     * only if WorkManager promotes the task to a foreground service. It is kept as unobtrusive
     * as possible: `PRIORITY_MIN`, silent, non-ongoing. Title resolution: the programmatic
     * [BaseKmpWorker.configNotificationTitle] override first, then the
     * `kmp_worker_notification_title` string resource (override per locale in `strings.xml`).
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val title = configNotificationTitle
            ?: applicationContext.getString(R.string.kmp_worker_notification_title)
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

    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)
            ?: run {
                Logger.e(LogTags.WORKER, "Worker not found: $workerClassName — not registered in factory")
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

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kmp_worker_tasks"
        private const val NOTIFICATION_ID = 0x4B4D5000.toInt()
    }
}
