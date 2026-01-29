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
     * Platform-specific logging implementation
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
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
