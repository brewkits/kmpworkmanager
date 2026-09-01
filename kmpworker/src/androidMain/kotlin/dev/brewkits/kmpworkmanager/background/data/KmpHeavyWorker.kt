package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerAndroid
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
            KmpWorkManagerAndroid.requireRegistry().androidWorkerFactory
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
         * The service `androidx.work` declares in its own AAR manifest — every app depending
         * on `work-runtime` merges this into its final manifest automatically, so it's always
         * present with SOME `foregroundServiceType`, never a component the host app authored
         * itself. Used to read the app's actual merged FGS type declaration for diagnostics.
         */
        private const val SYSTEM_FOREGROUND_SERVICE_CLASS = "androidx.work.impl.foreground.SystemForegroundService"

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
        // Diagnostic-only, best-effort read of the manifest's actual declared FGS type(s) for
        // this app. Never throws and never affects control flow on its own — a
        // getServiceInfo() failure (component genuinely absent, PackageManager quirk on a
        // given OEM, …) must not turn a worker that would otherwise succeed into a failure.
        // See docs/ROADMAP.md "Android FGS-type diagnosability" (#82) for why this exists:
        // the pre-fix exception message below named only what THIS worker declared, not
        // what the manifest actually has, and on API 29-33 a mismatch doesn't throw at all
        // (ForegroundServiceTypeException is API 34+) — proactively warn there since
        // nothing else will.
        //
        // `foregroundServiceType` on <service> is an OR-bitmask of possibly several types
        // (docs/ANDROID_FGS_GUIDE.md's own examples declare e.g. "dataSync|camera" so one
        // service backs workers of different types) — so this worker's single declared type
        // must be present as a BIT in the manifest value, not equal to it. `!=` here would
        // false-positive on every multi-type manifest, which is the guide's own recommended
        // pattern, and API < 29 or a manifest that omits foregroundServiceType entirely reads
        // back as `0` (FOREGROUND_SERVICE_TYPE_NONE / "manifest" default) — `0 != anything`
        // would also false-positive on every app that hasn't set a type at all, which is
        // common and not itself a mismatch worth warning about (only a positive, wrong bit is).
        val manifestFgsType = readManifestForegroundServiceType()
        if (manifestFgsType != null && manifestFgsType != 0 && (manifestFgsType and foregroundServiceType) == 0 &&
            Build.VERSION.SDK_INT in Build.VERSION_CODES.Q until Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Logger.w(
                LogTags.WORKER,
                "foregroundServiceType mismatch: worker declares $foregroundServiceType but the " +
                    "manifest's <service android:foregroundServiceType=…> declares $manifestFgsType " +
                    "(a bitmask — this worker's type is not one of the bits set there). " +
                    "On this OS version (API ${Build.VERSION.SDK_INT}, below Android 14) a mismatch " +
                    "does NOT throw — the FGS silently starts with the manifest's type instead of the " +
                    "one this worker expects. See docs/ANDROID_FGS_GUIDE.md.",
            )
        }

        // Handle Android 14+ FGS exceptions gracefully to prevent WorkManager crashes.
        // Translates raw exceptions into Result.failure() with diagnostic logs.
        try {
            setForeground(createForegroundInfo())
        } catch (e: SecurityException) {
            Logger.e(
                LogTags.WORKER,
                "Foreground service start denied (SecurityException). " +
                    "Type=$foregroundServiceType requires a matching FOREGROUND_SERVICE_<TYPE> " +
                    "permission in AndroidManifest.xml.${manifestTypeSuffix(manifestFgsType)} " +
                    "See docs/ANDROID_FGS_GUIDE.md.",
                e,
            )
            return Result.failure()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) and
            // ForegroundServiceTypeException (API 34+) both extend IllegalStateException.
            Logger.e(
                LogTags.WORKER,
                "Foreground service start not allowed: ${e::class.simpleName} — ${e.message}. " +
                    "This typically means: (a) the host app is in a state where it cannot start " +
                    "an FGS (background-without-special-permission), OR (b) the FGS type " +
                    "$foregroundServiceType is not declared on <service android:foregroundServiceType=…> " +
                    "in the manifest.${manifestTypeSuffix(manifestFgsType)} " +
                    "See docs/ANDROID_FGS_GUIDE.md for type-by-type setup.",
                e,
            )
            return Result.failure()
        }
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

    /**
     * Reads the `android:foregroundServiceType` this app's manifest actually declares for
     * WorkManager's `SystemForegroundService` — via [PackageManager.getServiceInfo], which
     * returns the fully merged manifest, not the source `AndroidManifest.xml` fragment this
     * library or the host app authored in isolation.
     *
     * Best-effort and diagnostic-only: returns `null` on ANY failure (component genuinely
     * absent — shouldn't happen since `androidx.work` declares it, but a `NameNotFoundException`
     * or OEM `PackageManager` quirk must not throw out of `doWork()`) or below API 29, where
     * `ServiceInfo.foregroundServiceType` doesn't exist. Callers must treat `null` as
     * "unknown," never as "zero" or "mismatch."
     */
    private fun readManifestForegroundServiceType(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val component = ComponentName(applicationContext, SYSTEM_FOREGROUND_SERVICE_CLASS)
            val info = applicationContext.packageManager.getServiceInfo(component, PackageManager.GET_SERVICES)
            info.foregroundServiceType
        } catch (e: Exception) {
            Logger.d(LogTags.WORKER, "Could not read manifest foregroundServiceType (non-fatal, diagnostics only): ${e.message}")
            null
        }
    }

    /**
     * Appends the manifest's actual declared type bitmask to a diagnostic message, when
     * known. Note this is a bitmask (possibly several `FOREGROUND_SERVICE_TYPE_*` bits
     * OR'd together on a shared `<service>` — see docs/ANDROID_FGS_GUIDE.md), not
     * necessarily equal to this worker's single [foregroundServiceType].
     */
    private fun manifestTypeSuffix(manifestFgsType: Int?): String =
        if (manifestFgsType != null) " Manifest currently declares type bitmask=$manifestFgsType." else ""
}
