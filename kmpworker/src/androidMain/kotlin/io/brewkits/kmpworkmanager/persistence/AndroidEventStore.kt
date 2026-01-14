package io.brewkits.kmpworkmanager.persistence

import android.content.Context
import io.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import io.brewkits.kmpworkmanager.utils.Logger
import io.brewkits.kmpworkmanager.utils.LogTags
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
 * {Context.filesDir}/io.brewkits.kmpworkmanager/events/events.jsonl
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
     * Base directory: {filesDir}/io.brewkits.kmpworkmanager/events/
     */
    private val baseDir: File by lazy {
        File(context.filesDir, "io.brewkits.kmpworkmanager/events").apply {
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

                // Auto-cleanup if enabled (probabilistic - 10% of writes)
                if (config.autoCleanup && Math.random() < 0.1) {
                    performCleanup()
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

                val allEvents = eventsFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            Logger.w(LogTags.SCHEDULER, "AndroidEventStore: Failed to parse event, skipping: ${e.message}")
                            null // Skip corrupted lines
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

                val allEvents = eventsFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            null
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

                val allEvents = eventsFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            null
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

                eventsFile.readLines().count { it.isNotBlank() }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "AndroidEventStore: Failed to get event count", e)
                0
            }
        }
    }

    /**
     * Performs cleanup of consumed and old events.
     * Called probabilistically during writes (10% chance).
     */
    private fun performCleanup() {
        try {
            if (!eventsFile.exists()) return

            val allEvents = eventsFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<StoredEvent>(line)
                    } catch (e: Exception) {
                        null
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
