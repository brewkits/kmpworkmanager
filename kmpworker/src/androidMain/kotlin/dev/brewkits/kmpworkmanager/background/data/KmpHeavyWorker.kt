package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Android implementation for "heavy" tasks that run as a foreground service.
 *
 * Difference from [KmpWorker]: calls [setForeground] before starting work, which posts a
 * persistent notification and elevates the process priority. Use this for long-running or
 * network-intensive tasks that must not be deferred by the OS.
 *
 * On API 29+ the foreground service type is explicitly declared in [ForegroundInfo].
 * Android 14 (API 34) made the type mandatory — using the wrong type causes a runtime
 * [android.app.ForegroundServiceStartNotAllowedException]. Override [foregroundServiceType]
 * in your subclass to match your workload. See `docs/ANDROID_FGS_GUIDE.md` for manifest
 * snippets per type.
 */
open class KmpHeavyWorker(
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
        try {
            KmpWorkManagerKoin.getKoin().get()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "KmpWorkManager not initialized — KmpHeavyWorker cannot start. " +
                "Call KmpWorkManager.initialize() in Application.onCreate() before WorkManager runs, " +
                "or migrate to KmpWorkerFactory for proper constructor injection (see KmpWorkerFactory KDoc).",
                e
            )
        }
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kmp_heavy_worker_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * Convenience aliases for the most common FGS types. These are the same integer
         * constants as [ServiceInfo.FOREGROUND_SERVICE_TYPE_*] but exposed here so
         * subclasses do not need a separate `android.content.pm.ServiceInfo` import.
         *
         * Pass one of these to [foregroundServiceType] in your subclass:
         * ```kotlin
         * class VideoUploadWorker(...) : KmpHeavyWorker(...) {
         *     override val foregroundServiceType = FGS_DATA_SYNC   // upload / sync
         * }
         * class ImageProcessingWorker(...) : KmpHeavyWorker(...) {
         *     override val foregroundServiceType = FGS_MEDIA_PROCESSING  // requires API 35
         * }
         * ```
         * **Manifest:** each type requires a matching `<uses-permission>` and
         * `android:foregroundServiceType` on `SystemForegroundService`. See
         * `docs/ANDROID_FGS_GUIDE.md`.
         */
        @JvmField val FGS_DATA_SYNC: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        @JvmField val FGS_MEDIA_PLAYBACK: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        @JvmField val FGS_CAMERA: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA          // API 29+
        @JvmField val FGS_LOCATION: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION       // API 29+
        @JvmField val FGS_CONNECTED_DEVICE: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE // API 29+

        /**
         * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING] — Android 15 / API 35.
         *
         * Use for on-device image/video compression, transcoding, or format conversion.
         * Requires `android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING` and
         * `android:foregroundServiceType="mediaProcessing"` in the host manifest. Guard
         * usage with `Build.VERSION.SDK_INT >= 35` to avoid crashes on older devices.
         */
        const val FGS_MEDIA_PROCESSING: Int = 0x1000  // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    }

    /**
     * Foreground service type passed to [ForegroundInfo] on API 29+.
     *
     * Defaults to [FGS_DATA_SYNC] ([ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]),
     * which covers HTTP uploads, downloads, and sync workloads.
     *
     * Override in your subclass for camera-specific workloads:
     *
     * | Use case | Type constant | Min API | Permission needed |
     * |---|---|---|---|
     * | Upload / download / sync | `FGS_DATA_SYNC` | 29 | `FOREGROUND_SERVICE_DATA_SYNC` |
     * | Image / video transcoding | `FGS_MEDIA_PROCESSING` | 35 | `FOREGROUND_SERVICE_MEDIA_PROCESSING` |
     * | Audio / video playback | `FGS_MEDIA_PLAYBACK` | 29 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
     * | Camera capture | `FGS_CAMERA` | 29 | `FOREGROUND_SERVICE_CAMERA` |
     * | GPS / geofence | `FGS_LOCATION` | 29 | `FOREGROUND_SERVICE_LOCATION` |
     *
     * See `docs/ANDROID_FGS_GUIDE.md` for ready-to-paste manifest snippets.
     */
    protected open val foregroundServiceType: Int get() = FGS_DATA_SYNC

    override val workerLogTag: String get() = "KmpHeavyWorker"

    override suspend fun doWork(): Result {
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, foregroundServiceType)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
