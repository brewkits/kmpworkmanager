package io.kmp.taskmanager.sample.utils

/**
 * Professional logging utility for KMP TaskManager.
 * Provides structured logging with levels, tags, and platform-specific formatting.
 */
object Logger {

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Log debug message - verbose information for development
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG, tag, message, throwable)
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
            Level.DEBUG -> "🔍"
            Level.INFO -> "ℹ️"
            Level.WARN -> "⚠️"
            Level.ERROR -> "❌"
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
    const val ALARM = "ExactAlarm"
    const val PERMISSION = "Permission"
    const val PUSH = "PushNotification"
    const val DEBUG = "Debug"
    const val ERROR = "Error"
}
