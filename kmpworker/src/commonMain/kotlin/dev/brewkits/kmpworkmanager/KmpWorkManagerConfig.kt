package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger

/**
 * Configuration for KmpWorkManager initialization.
 *
 * Example:
 * ```
 * val config = KmpWorkManagerConfig(
 *     logLevel = Logger.Level.INFO,  // Only log INFO and above in production
 *     customLogger = MyCustomLogger(),
 *     minFreeDiskSpaceBytes = 25_000_000L  // 25MB for older/low-storage devices
 * )
 *
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpWorkerModule(workerFactory = MyWorkerFactory(), config = config))
 * }
 * ```
 *
 * @param logLevel Minimum log level to output. Default: INFO (production-friendly)
 * @param customLogger Custom logger implementation for routing logs to analytics/crash reporting. Default: null
 * @param minFreeDiskSpaceBytes Minimum free disk space required before writing task data (iOS only).
 *   Default: 50MB. Reduce to 25MB for apps targeting older or low-storage devices (32GB/64GB).
 *   The scheduler rejects writes when free space falls below this threshold to prevent
 *   corruption on nearly-full devices.
 * @param androidForegroundNotificationTitle (Android only) Title shown in the system notification
 *   when the OS promotes a non-heavy task to a foreground service (e.g. under memory pressure).
 *   `null` falls back to the string resource `kmp_worker_notification_title` (overridable per
 *   locale in your app's `res/values-xx/strings.xml`). Set an explicit value here to override
 *   the resource without forking strings — useful for white-label or multi-tenant apps.
 */
data class KmpWorkManagerConfig(
    val logLevel: Logger.Level = Logger.Level.INFO,
    val customLogger: CustomLogger? = null,
    val minFreeDiskSpaceBytes: Long = 50_000_000L,
    val androidForegroundNotificationTitle: String? = null
)
