package dev.brewkits.kmpworkmanager.sample.logs

import dev.brewkits.kmpworkmanager.sample.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Represents a single log entry with timestamp, level, tag, and message.
 */
data class LogEntry(
    val timestamp: Long,
    val level: Logger.Level,
    val tag: String,
    val message: String
)

/**
 * In-memory circular buffer storing log entries.
 * Thread-safe with Mutex. Auto-cleanup old entries (keep last 1 hour).
 * Maximum 500 entries retained.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
object LogStore {
    private const val MAX_ENTRIES = 500
    private const val MAX_AGE_MS = 60 * 60 * 1000L // 1 hour

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /**
     * StateFlow of all log entries, exposed for UI observation
     */
    val logs: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /**
     * Add a new log entry to the store.
     * Automatically removes old entries if limit is exceeded or entries are older than 1 hour.
     */
    suspend fun add(entry: LogEntry) {
        mutex.withLock {
            val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val updatedList = (_entries.value + entry)
                .filter { (currentTime - it.timestamp) < MAX_AGE_MS } // Remove entries older than 1 hour
                .takeLast(MAX_ENTRIES) // Keep only last MAX_ENTRIES

            _entries.value = updatedList
        }
    }

    /**
     * Clear all log entries
     */
    suspend fun clear() {
        mutex.withLock {
            _entries.value = emptyList()
        }
    }

    /**
     * Get all logs filtered by tag
     */
    fun getByTag(tag: String): List<LogEntry> {
        return _entries.value.filter { it.tag == tag }
    }

    /**
     * Get all logs filtered by level
     */
    fun getByLevel(level: Logger.Level): List<LogEntry> {
        return _entries.value.filter { it.level == level }
    }

    /**
     * Search logs by message content
     */
    fun search(query: String): List<LogEntry> {
        if (query.isBlank()) return _entries.value
        return _entries.value.filter {
            it.message.contains(query, ignoreCase = true) ||
            it.tag.contains(query, ignoreCase = true)
        }
    }
}
