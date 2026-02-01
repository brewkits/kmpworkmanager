package dev.brewkits.kmpworkmanager.sample.utils

import dev.brewkits.kmpworkmanager.sample.logs.LogEntry
import dev.brewkits.kmpworkmanager.sample.logs.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Professional logging utility for KMP WorkManager.
 * Provides structured logging with levels, tags, and platform-specific formatting.
 * Emits all logs to LogStore for UI display.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
object Logger {
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class Level {
        DEBUG_LEVEL,
        INFO,
        WARN,
        ERROR
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
     * Also emits to LogStore for UI display
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        val formattedMessage = formatMessage(level, tag, message, throwable)
        platformLog(level, formattedMessage)

        // Emit to LogStore asynchronously (non-blocking)
        loggerScope.launch {
            LogStore.add(
                LogEntry(
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    level = level,
                    tag = tag,
                    message = if (throwable != null) {
                        "$message\n${throwable.stackTraceToString()}"
                    } else {
                        message
                    }
                )
            )
        }
    }

    /**
     * Format message with level indicator
     */
    private fun formatMessage(level: Level, tag: String, message: String, throwable: Throwable?): String {
        val levelIcon = when (level) {
            Level.DEBUG_LEVEL -> "🔍"
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
    const val TAG_DEBUG = "Debug"
    const val ERROR = "Error"
    const val QUEUE = "TaskQueue"
    const val UI = "UI"
    const val PLATFORM = "Platform"
}
