package dev.brewkits.kmpworkmanager.utils

/**
 * Professional logging utility for KMP WorkManager.
 * Provides structured logging with levels, tags, and platform-specific formatting.
 *
 */
object Logger {

    enum class Level {
        VERBOSE,      // High-frequency operational details
        DEBUG_LEVEL,  // Development-time debugging information
        INFO,         // General informational messages
        WARN,         // Potentially harmful situations
        ERROR         // Error events that might still allow the app to continue
    }

    /**
     * Minimum log level to output. Logs below this level are filtered out.
     * Default: VERBOSE (log everything) for backward compatibility.
     * @Volatile ensures visibility across threads
     */
    @Volatile
    private var minLevel: Level = Level.VERBOSE

    /**
     * Custom logger implementation. If set, delegates all logging to this instance.
     * @Volatile ensures visibility across threads
     */
    @Volatile
    private var customLogger: CustomLogger? = null

    /**
     * Lock object for thread-safe configuration changes
     */
    private val configLock = Any()

    /**
     * Set the minimum log level (thread-safe). Logs below this level will be filtered out.
     *
     * Example:
     * ```
     * Logger.setMinLevel(Logger.Level.INFO)  // Only log INFO, WARN, ERROR
     * ```
     */
    fun setMinLevel(level: Level) {
        synchronized(configLock) {
            minLevel = level
            i(LogTags.TAG_DEBUG, "Logger minimum level set to: $level")
        }
    }

    /**
     * Set a custom logger implementation (thread-safe). All logs will be delegated to this logger.
     *
     * Example:
     * ```
     * Logger.setCustomLogger(object : CustomLogger {
     *     override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
     *         // Send to analytics service
     *     }
     * })
     * ```
     */
    fun setCustomLogger(logger: CustomLogger?) {
        synchronized(configLock) {
            customLogger = logger
            logger?.let {
                i(LogTags.TAG_DEBUG, "Custom logger set: ${logger::class.simpleName}")
            }
        }
    }

    /**
     * Log verbose message - high-frequency operational details
     *
     * Examples: Individual enqueue/dequeue operations, byte-level I/O
     */
    fun v(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.VERBOSE, tag, message, throwable)
    }

    /**
     * Log debug message - verbose information for development
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG_LEVEL, tag, message, throwable)
    }

    /**
     * Log info message - general informational messages
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.INFO, tag, message, throwable)
    }

    /**
     * Log warning message - potentially harmful situations
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    /**
     * Log error message - error events that might still allow the app to continue
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * Platform-specific logging implementation with filtering
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        // Filter by minimum level
        if (level.ordinal < minLevel.ordinal) {
            return  // Skip logs below minimum level
        }

        // Delegate to custom logger if set
        customLogger?.let {
            it.log(level, tag, message, throwable)
            return
        }

        // Default: platform-specific logging
        val formattedMessage = formatMessage(level, tag, message, throwable)
        platformLog(level, formattedMessage)
    }

    /**
     * Format message with level indicator
     */
    private fun formatMessage(level: Level, tag: String, message: String, throwable: Throwable?): String {
        val levelIcon = when (level) {
            Level.VERBOSE -> "💬"        // Verbose operational details
            Level.DEBUG_LEVEL -> "🔍"   // Debug information
            Level.INFO -> "ℹ️"           // Informational
            Level.WARN -> "⚠️"           // Warning
            Level.ERROR -> "❌"          // Error
        }

        val baseMessage = "$levelIcon [$tag] $message"

        return if (throwable != null) {
            "$baseMessage\n${throwable.stackTraceToString()}"
        } else {
            baseMessage
        }
    }

    /**
     * Platform-specific logging - implemented in expect/actual
     */
    private fun platformLog(level: Level, message: String) {
        LoggerPlatform.log(level, message)
    }
}

/**
 * Platform-specific logger implementation
 */
internal expect object LoggerPlatform {
    fun log(level: Logger.Level, message: String)
}

/**
 * Custom logger interface for delegating log output.
 * Implement this interface to send logs to custom destinations (analytics, crash reporting, etc.)
 *
 * Example:
 * ```
 * class FirebaseLogger : CustomLogger {
 *     override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
 *         when (level) {
 *             Logger.Level.ERROR -> FirebaseCrashlytics.log("ERROR: [$tag] $message")
 *             Logger.Level.WARN -> FirebaseCrashlytics.log("WARN: [$tag] $message")
 *             else -> println("[$tag] $message")
 *         }
 *         throwable?.let { FirebaseCrashlytics.recordException(it) }
 *     }
 * }
 * ```
 */
interface CustomLogger {
    /**
     * Log a message with the specified level, tag, and optional throwable.
     *
     * @param level The log level (VERBOSE, DEBUG_LEVEL, INFO, WARN, ERROR)
     * @param tag The log tag for categorization
     * @param message The log message
     * @param throwable Optional exception to log
     */
    fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?)
}

/**
 * Predefined log tags for consistent logging across the app
 */
object LogTags {
    const val SCHEDULER = "TaskScheduler"
    const val WORKER = "TaskWorker"
    const val CHAIN = "TaskChain"
    const val QUEUE = "Queue"
    const val ALARM = "ExactAlarm"
    const val PERMISSION = "Permission"
    const val PUSH = "PushNotification"
    const val TAG_DEBUG = "Debug"
    const val ERROR = "Error"
}
