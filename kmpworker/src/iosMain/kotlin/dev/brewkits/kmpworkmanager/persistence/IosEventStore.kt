package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * iOS implementation of EventStore using file-based storage.
 *
 * Features:
 * - JSONL (JSON Lines) format for efficient append operations
 * - Thread-safe operations using Mutex + NSFileCoordinator
 * - Atomic writes using NSFileCoordinator for coordination
 * - Automatic cleanup of old/consumed events
 * - Zero external dependencies (uses Foundation APIs)
 *
 * Storage Location:
 * Library/Application Support/dev.brewkits.kmpworkmanager/events/events.jsonl
 *
 * Performance:
 * - Write: ~5ms (append to file)
 * - Read: ~50ms (scan 1000 events)
 * - Storage: ~200KB (1000 events × 200 bytes)
 */
@OptIn(ExperimentalForeignApi::class)
class IosEventStore(
    private val config: EventStoreConfig = EventStoreConfig()
) : EventStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val fileManager = NSFileManager.defaultManager
    private val fileLock = Mutex()

    @kotlin.concurrent.Volatile
    private var lastCleanupTimeMs: Long = 0L

    /**
     * Base directory: Library/Application Support/dev.brewkits.kmpworkmanager/events/
     */
    private val baseDir: NSURL by lazy {
        val urls = fileManager.URLsForDirectory(
            NSApplicationSupportDirectory,
            NSUserDomainMask
        ) as List<*>
        val appSupportDir = urls.firstOrNull() as? NSURL
            ?: throw IllegalStateException("Could not locate Application Support directory")

        val eventsDirURL = appSupportDir
            .safeAppend("dev.brewkits.kmpworkmanager")
            .safeAppend("events")

        ensureDirectoryExists(eventsDirURL)

        Logger.d(LogTags.SCHEDULER, "IosEventStore: Initialized at ${eventsDirURL.path}")
        eventsDirURL
    }

    /**
     * Events file: events.jsonl
     */
    private val eventsFileURL: NSURL by lazy {
        baseDir.safeAppend("events.jsonl").also { url ->
            if (!fileManager.fileExistsAtPath(url.path ?: "")) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val success = ("" as NSString).writeToURL(
                        url,
                        atomically = true,
                        encoding = NSUTF8StringEncoding,
                        error = errorPtr.ptr
                    )
                    if (!success) {
                        val msg = errorPtr.value?.localizedDescription ?: "unknown error"
                        Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to create events file at ${url.path}: $msg")
                    } else {
                        Logger.d(LogTags.SCHEDULER, "IosEventStore: Created events file at ${url.path}")
                    }
                }
            }
        }
    }

    override suspend fun saveEvent(event: TaskCompletionEvent): String = fileLock.withLock {
        val eventId = NSUUID.UUID().UUIDString
        val storedEvent = StoredEvent(
            id = eventId,
            event = event,
            timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
            consumed = false
        )

        try {
            // Read existing content
            val existingContent = readFileContent(eventsFileURL) ?: ""

            // Append new event as JSONL
            val line = json.encodeToString(storedEvent)
            val newContent = if (existingContent.isEmpty()) {
                line + "\n"
            } else {
                existingContent + line + "\n"
            }

            // Write atomically
            writeFileAtomic(eventsFileURL, newContent)

            Logger.d(LogTags.SCHEDULER, "IosEventStore: Saved event $eventId for task ${event.taskName}")

            // Auto-cleanup if enabled (deterministic - time-based or size-based, matching Android)
            if (config.autoCleanup && shouldPerformCleanup()) {
                performCleanup()
                lastCleanupTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
            }

            eventId
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to save event", e)
            throw e
        }
    }

    override suspend fun getUnconsumedEvents(): List<StoredEvent> {
        return fileLock.withLock {
            try {
                val content = readFileContent(eventsFileURL) ?: return@withLock emptyList()

                if (content.isEmpty()) {
                    return@withLock emptyList()
                }

                val allEvents = content.split("\n")
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            Logger.w(LogTags.SCHEDULER, "IosEventStore: Failed to parse event, skipping")
                            null
                        }
                    }

                val unconsumed = allEvents
                    .filter { !it.consumed }
                    .sortedBy { it.timestamp }

                Logger.d(LogTags.SCHEDULER, "IosEventStore: Retrieved ${unconsumed.size} unconsumed events (${allEvents.size} total)")

                unconsumed
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to read events", e)
                emptyList()
            }
        }
    }

    override suspend fun markEventConsumed(eventId: String) {
        fileLock.withLock {
            try {
                val content = readFileContent(eventsFileURL) ?: return@withLock

                val allEvents = content.split("\n")
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            null
                        }
                    }

                val updatedEvents = allEvents.map { event ->
                    if (event.id == eventId) {
                        event.copy(consumed = true)
                    } else {
                        event
                    }
                }

                writeEventsAtomic(updatedEvents)

                Logger.d(LogTags.SCHEDULER, "IosEventStore: Marked event $eventId as consumed")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to mark event consumed", e)
            }
        }
    }

    override suspend fun clearOldEvents(olderThanMs: Long): Int {
        return fileLock.withLock {
            try {
                val content = readFileContent(eventsFileURL) ?: return@withLock 0

                val allEvents = content.split("\n")
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<StoredEvent>(line)
                        } catch (e: Exception) {
                            null
                        }
                    }

                val cutoffTime = (NSDate().timeIntervalSince1970 * 1000).toLong() - olderThanMs
                val eventsToKeep = allEvents.filter { it.timestamp > cutoffTime }
                val deletedCount = allEvents.size - eventsToKeep.size

                if (deletedCount > 0) {
                    writeEventsAtomic(eventsToKeep)
                    Logger.i(LogTags.SCHEDULER, "IosEventStore: Deleted $deletedCount old events")
                }

                deletedCount
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to clear old events", e)
                0
            }
        }
    }

    override suspend fun clearAll() {
        fileLock.withLock {
            try {
                writeFileAtomic(eventsFileURL, "")
                Logger.i(LogTags.SCHEDULER, "IosEventStore: Cleared all events")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to clear all events", e)
            }
        }
    }

    override suspend fun getEventCount(): Int {
        return fileLock.withLock {
            try {
                val content = readFileContent(eventsFileURL) ?: return@withLock 0
                content.split("\n").count { it.isNotBlank() }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to get event count", e)
                0
            }
        }
    }

    /**
     * Deterministic cleanup check (mirrors Android logic).
     * Triggers if cleanup interval has elapsed OR file size exceeds threshold.
     * Must be called while holding [fileLock].
     */
    private fun shouldPerformCleanup(): Boolean {
        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Check 1: Time-based (cleanup interval elapsed)
        if (now - lastCleanupTimeMs >= config.cleanupIntervalMs) {
            Logger.d(LogTags.SCHEDULER, "IosEventStore: Cleanup triggered by time (${now - lastCleanupTimeMs}ms since last)")
            return true
        }

        // Check 2: Size-based (file exceeds threshold)
        val filePath = eventsFileURL.path ?: return false
        memScoped {
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(filePath, error = null)
            val fileSize = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
            if (fileSize >= config.cleanupFileSizeThresholdBytes) {
                Logger.d(LogTags.SCHEDULER, "IosEventStore: Cleanup triggered by file size (${fileSize / 1024}KB)")
                return true
            }
        }

        return false
    }

    /**
     * Performs cleanup of consumed and old events.
     */
    private fun performCleanup() {
        try {
            val content = readFileContent(eventsFileURL) ?: return

            val allEvents = content.split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<StoredEvent>(line)
                    } catch (e: Exception) {
                        null
                    }
                }

            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
            val eventsToKeep = allEvents.filter { event ->
                val age = now - event.timestamp
                when {
                    event.consumed && age.compareTo(config.consumedEventRetentionMs) > 0 -> false
                    !event.consumed && age.compareTo(config.unconsumedEventRetentionMs) > 0 -> false
                    else -> true
                }
            }

            val finalEvents = if (eventsToKeep.size > config.maxEvents) {
                eventsToKeep.sortedByDescending { it.timestamp }.take(config.maxEvents)
            } else {
                eventsToKeep
            }

            if (finalEvents.size < allEvents.size) {
                writeEventsAtomic(finalEvents)
                Logger.d(LogTags.SCHEDULER, "IosEventStore: Cleanup removed ${allEvents.size - finalEvents.size} events")
            }
        } catch (e: Exception) {
            Logger.w(LogTags.SCHEDULER, "IosEventStore: Cleanup failed", e)
        }
    }

    /**
     * Writes events to file atomically
     */
    private fun writeEventsAtomic(events: List<StoredEvent>) {
        val content = events.joinToString("\n") { event ->
            json.encodeToString(event)
        } + if (events.isNotEmpty()) "\n" else ""

        writeFileAtomic(eventsFileURL, content)
    }

    /**
     * Reads file content safely using IosFileCoordinator (handles test-mode detection)
     */
    private fun readFileContent(url: NSURL): String? {
        return try {
            IosFileCoordinator.coordinate(url, write = false) { fileURL ->
                NSString.stringWithContentsOfURL(
                    fileURL,
                    encoding = NSUTF8StringEncoding,
                    error = null
                )?.toString()
            }
        } catch (e: Exception) {
            Logger.w(LogTags.SCHEDULER, "IosEventStore: Read error: ${e.message}")
            null
        }
    }

    /**
     * Writes file content atomically using IosFileCoordinator (handles test-mode detection).
     * Write errors (disk full, permissions) are captured and propagated as exceptions.
     */
    private fun writeFileAtomic(url: NSURL, content: String) {
        var writeFailureMsg: String? = null

        IosFileCoordinator.coordinate(url, write = true) { fileURL ->
            memScoped {
                val writeErrorPtr = alloc<ObjCObjectVar<NSError?>>()
                val success = (content as NSString).writeToURL(
                    fileURL,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = writeErrorPtr.ptr
                )
                if (!success) {
                    writeFailureMsg = writeErrorPtr.value?.localizedDescription
                        ?: "Write returned false"
                }
            }
        }

        writeFailureMsg?.let { msg ->
            throw IllegalStateException("IosEventStore: Write error: $msg")
        }
    }

    /**
     * Ensures directory exists, creating if necessary
     */
    private fun ensureDirectoryExists(url: NSURL) {
        if (!fileManager.fileExistsAtPath(url.path ?: "")) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = errorPtr.ptr
                )

                val error = errorPtr.value
                if (error != null) {
                    throw IllegalStateException("Failed to create directory: ${error.localizedDescription}")
                }
            }
        }
    }
}

/**
 * Safe URL path component appending — replaces URLByAppendingPathComponent(x)!!
 */
private fun NSURL.safeAppend(component: String): NSURL =
    URLByAppendingPathComponent(component)
        ?: throw IllegalStateException("Failed to construct URL: base='$path' component='$component'")
