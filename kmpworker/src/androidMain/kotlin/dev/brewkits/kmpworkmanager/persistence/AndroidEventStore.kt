package dev.brewkits.kmpworkmanager.persistence

import android.content.Context
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Android implementation of EventStore using file-based storage.
 *
 * Features:
 * - JSONL (JSON Lines) format for efficient append operations
 * - Thread-safe operations using synchronized blocks
 * - Atomic writes using temp file + rename pattern
 * - Automatic cleanup of old/consumed events
 * - Zero external dependencies (uses standard Java File I/O)
 *
 * Storage Location:
 * {Context.filesDir}/dev.brewkits.kmpworkmanager/events/events.jsonl
 *
 * Performance:
 * - Write: ~5ms (append to file)
 * - Read: ~50ms (scan 1000 events)
 * - Storage: ~200KB (1000 events × 200 bytes)
 */
class AndroidEventStore(
    private val context: Context,
    private val config: EventStoreConfig = EventStoreConfig()
) : EventStore {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Base directory: {filesDir}/dev.brewkits.kmpworkmanager/events/
     */
    private val baseDir: File by lazy {
        File(context.filesDir, "dev.brewkits.kmpworkmanager/events").apply {
            if (!exists()) {
                mkdirs()
                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Created directory at $absolutePath")
            }
        }
    }

    /**
     * Events file: events.jsonl (JSON Lines format)
     */
    private val eventsFile: File by lazy {
        File(baseDir, "events.jsonl").apply {
            if (!exists()) {
                createNewFile()
                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Created events file at $absolutePath")
            }
        }
    }

    /**
     * Lock object for thread-safe file operations
     */
    private val fileLock = Any()

    /**
     * FIX: Track last cleanup time for deterministic cleanup (v2.2.2+)
     * Replaces probabilistic 10% cleanup with time-based strategy
     */
    @Volatile
    private var lastCleanupTimeMs: Long = 0L

    override suspend fun saveEvent(event: TaskCompletionEvent): String = withContext(Dispatchers.IO) {
        val eventId = UUID.randomUUID().toString()
        val storedEvent = StoredEvent(
            id = eventId,
            event = event,
            timestamp = System.currentTimeMillis(),
            consumed = false
        )

        synchronized(fileLock) {
            try {
                // Append event as JSONL (JSON Lines - one line per event)
                val line = json.encodeToString(storedEvent)
                eventsFile.appendText(line + "\n")

                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Saved event $eventId for task ${event.taskName}")

                // FIX: Deterministic cleanup (v2.2.2+) - time-based or size-based
                if (config.autoCleanup && shouldPerformCleanup()) {
                    performCleanup()
                    lastCleanupTimeMs = System.currentTimeMillis()
                }

                eventId
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to save event", e)
                throw e
            }
        }
    }

    override suspend fun getUnconsumedEvents(): List<StoredEvent> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (!eventsFile.exists() || eventsFile.length() == 0L) {
                    return@withContext emptyList()
                }

                // FIX: Use streaming reader instead of readLines() to prevent OOM
                val allEvents = mutableListOf<StoredEvent>()
                eventsFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                allEvents.add(json.decodeFromString<StoredEvent>(line))
                            } catch (e: Exception) {
                                Logger.w(LogTags.SCHEDULER, "AndroidEventStore: Failed to parse event, skipping: ${e.message}")
                                // Skip corrupted lines
                            }
                        }
                    }
                }

                // Filter unconsumed events and sort by timestamp
                val unconsumed = allEvents
                    .filter { !it.consumed }
                    .sortedBy { it.timestamp }

                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Retrieved ${unconsumed.size} unconsumed events (${allEvents.size} total)")

                unconsumed
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to read events", e)
                emptyList()
            }
        }
    }

    override suspend fun markEventConsumed(eventId: String) = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (!eventsFile.exists()) return@withContext

                // FIX: Use streaming reader instead of readLines() to prevent OOM
                val allEvents = mutableListOf<StoredEvent>()
                eventsFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                allEvents.add(json.decodeFromString<StoredEvent>(line))
                            } catch (e: Exception) {
                                // Skip corrupted lines
                            }
                        }
                    }
                }

                // Mark event as consumed
                val updatedEvents = allEvents.map { event ->
                    if (event.id == eventId) {
                        event.copy(consumed = true)
                    } else {
                        event
                    }
                }

                // Write back atomically
                writeEventsAtomic(updatedEvents)

                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Marked event $eventId as consumed")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to mark event consumed", e)
            }
        }
    }

    override suspend fun clearOldEvents(olderThanMs: Long): Int = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (!eventsFile.exists()) return@withContext 0

                // FIX: Use streaming reader instead of readLines() to prevent OOM
                val allEvents = mutableListOf<StoredEvent>()
                eventsFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                allEvents.add(json.decodeFromString<StoredEvent>(line))
                            } catch (e: Exception) {
                                // Skip corrupted lines
                            }
                        }
                    }
                }

                val cutoffTime = System.currentTimeMillis() - olderThanMs
                val eventsToKeep = allEvents.filter { it.timestamp > cutoffTime }
                val deletedCount = allEvents.size - eventsToKeep.size

                if (deletedCount > 0) {
                    writeEventsAtomic(eventsToKeep)
                    Logger.i(LogTags.SCHEDULER, "AndroidEventStore: Deleted $deletedCount old events")
                }

                deletedCount
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to clear old events", e)
                0
            }
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (eventsFile.exists()) {
                    eventsFile.delete()
                    eventsFile.createNewFile()
                    Logger.i(LogTags.SCHEDULER, "AndroidEventStore: Cleared all events")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to clear all events", e)
            }
        }
    }

    override suspend fun getEventCount(): Int = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (!eventsFile.exists() || eventsFile.length() == 0L) {
                    return@withContext 0
                }

                // FIX: Use streaming reader instead of readLines() to prevent OOM
                var count = 0
                eventsFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            count++
                        }
                    }
                }
                count
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to get event count", e)
                0
            }
        }
    }

    /**
     * FIX: Deterministic cleanup check (v2.2.2+)
     * Replaces probabilistic 10% cleanup with time-based + size-based strategy
     *
     * Triggers cleanup if:
     * 1. Cleanup interval has elapsed (default: 5 minutes), OR
     * 2. File size exceeds threshold (default: 1MB)
     *
     * @return true if cleanup should be performed
     */
    private fun shouldPerformCleanup(): Boolean {
        val now = System.currentTimeMillis()

        // Check 1: Time-based (cleanup interval elapsed)
        val timeSinceLastCleanup = now - lastCleanupTimeMs
        if (timeSinceLastCleanup >= config.cleanupIntervalMs) {
            Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Cleanup triggered by time (${timeSinceLastCleanup}ms since last cleanup)")
            return true
        }

        // Check 2: Size-based (file exceeds threshold)
        if (eventsFile.exists()) {
            val fileSize = eventsFile.length()
            if (fileSize >= config.cleanupFileSizeThresholdBytes) {
                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Cleanup triggered by file size (${fileSize / 1024}KB)")
                return true
            }
        }

        return false
    }

    /**
     * Performs cleanup of consumed and old events.
     * FIX: Now called deterministically based on time or file size (was probabilistic 10%).
     */
    private fun performCleanup() {
        try {
            if (!eventsFile.exists()) return

            // FIX: Use streaming reader instead of readLines() to prevent OOM
            val allEvents = mutableListOf<StoredEvent>()
            eventsFile.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            allEvents.add(json.decodeFromString<StoredEvent>(line))
                        } catch (e: Exception) {
                            // Skip corrupted lines
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            val eventsToKeep = allEvents.filter { event ->
                when {
                    // Remove consumed events older than retention period
                    event.consumed && (now - event.timestamp) > config.consumedEventRetentionMs -> false
                    // Remove unconsumed events older than retention period
                    !event.consumed && (now - event.timestamp) > config.unconsumedEventRetentionMs -> false
                    else -> true
                }
            }

            // Enforce max events limit (keep most recent)
            val finalEvents = if (eventsToKeep.size > config.maxEvents) {
                eventsToKeep.sortedByDescending { it.timestamp }.take(config.maxEvents)
            } else {
                eventsToKeep
            }

            if (finalEvents.size < allEvents.size) {
                writeEventsAtomic(finalEvents)
                Logger.d(LogTags.SCHEDULER, "AndroidEventStore: Cleanup removed ${allEvents.size - finalEvents.size} events")
            }
        } catch (e: Exception) {
            Logger.w(LogTags.SCHEDULER, "AndroidEventStore: Cleanup failed", e)
        }
    }

    /**
     * Writes events to file atomically using temp file + rename pattern.
     * This prevents corruption if process is killed during write.
     */
    private fun writeEventsAtomic(events: List<StoredEvent>) {
        val tempFile = File(baseDir, "events.jsonl.tmp")

        try {
            // Write to temp file
            tempFile.bufferedWriter().use { writer ->
                events.forEach { event ->
                    val line = json.encodeToString(event)
                    writer.write(line)
                    writer.newLine()
                }
            }

            // Atomic rename (overwrites existing file)
            if (!tempFile.renameTo(eventsFile)) {
                throw IllegalStateException("Failed to rename temp file to events file")
            }
        } catch (e: Exception) {
            // Clean up temp file on error
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }
}
