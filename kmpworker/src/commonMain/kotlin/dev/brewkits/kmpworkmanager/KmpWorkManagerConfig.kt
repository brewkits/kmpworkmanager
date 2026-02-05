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
 *     customLogger = MyCustomLogger()
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
 */
data class KmpWorkManagerConfig(
    val logLevel: Logger.Level = Logger.Level.INFO,
    val customLogger: CustomLogger? = null
)
