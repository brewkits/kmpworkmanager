package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.data.IosDispatchers
import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * iOS implementation of EventStore using file-based storage.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEventStore(
    private val config: EventStoreConfig = EventStoreConfig()
) : EventStore {

    private val json = KmpWorkManagerRuntime.json
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
            .URLByAppendingPathComponent("dev.brewkits.kmpworkmanager", isDirectory = true)
            ?.URLByAppendingPathComponent("events", isDirectory = true)
            ?: throw IllegalStateException("Failed to construct events directory URL")

        ensureDirectoryExists(eventsDirURL)
        eventsDirURL
    }

    /**
     * Events file: events.jsonl
     */
    private val eventsFileURL: NSURL by lazy {
        val url = baseDir.URLByAppendingPathComponent("events.jsonl")
            ?: throw IllegalStateException("Failed to construct events.jsonl URL")
        if (!fileManager.fileExistsAtPath(url.path ?: "")) {
            fileManager.createFileAtPath(url.path ?: "", null, null)
        }
        url
    }

    override suspend fun saveEvent(event: TaskCompletionEvent): String = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            val eventId = NSUUID.UUID().UUIDString
            val storedEvent = StoredEvent(
                id = eventId,
                event = event,
                timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
                consumed = false
            )

            try {
                // Atomicity: entire append must be inside coordination
                IosFileCoordinator.coordinate(eventsFileURL, write = true) { safeUrl ->
                    // Disk space guard
                    val usableSpace = checkFreeSpace(safeUrl)
                    if (usableSpace < 1024 * 1024L) {
                        Logger.e(LogTags.SCHEDULER, "IosEventStore: disk critically low, skipping save")
                        return@coordinate
                    }

                    val line = json.encodeToString(storedEvent) + "\n"
                    val path = safeUrl.path ?: return@coordinate
                    memScoped {
                        val nsLine = line as NSString
                        val data = nsLine.dataUsingEncoding(NSUTF8StringEncoding) ?: return@coordinate
                        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
                        if (handle != null) {
                            handle.seekToEndOfFile()
                            handle.writeData(data)
                            handle.closeFile()
                        } else {
                            fileManager.createFileAtPath(path, data, null)
                        }
                    }
                }

                Logger.d(LogTags.SCHEDULER, "IosEventStore: Saved event $eventId for task ${event.taskName}")

                if (config.autoCleanup && shouldPerformCleanup()) {
                    performCleanupInternal()
                    lastCleanupTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                }

                eventId
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to save event", e)
                throw e
            }
        }
    }

    override suspend fun getUnconsumedEvents(): List<StoredEvent> = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            try {
                val lines = readAllLines()
                lines.mapNotNull<String, StoredEvent> { line ->
                    runCatching { json.decodeFromString<StoredEvent>(line) }.getOrNull()
                }.filter { !it.consumed }.sortedBy { it.timestamp }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to read events", e)
                emptyList()
            }
        }
    }

    override suspend fun markEventConsumed(eventId: String) = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            try {
                val lines = readAllLines()
                val updated = lines.mapNotNull<String, StoredEvent> { line ->
                    val event = runCatching { json.decodeFromString<StoredEvent>(line) }.getOrNull()
                    if (event?.id == eventId) event?.copy(consumed = true) else event
                }
                writeEventsAtomic(updated)
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to mark event consumed", e)
            }
        }
    }

    override suspend fun clearOldEvents(olderThanMs: Long): Int = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            try {
                val lines = readAllLines()
                val cutoff = (NSDate().timeIntervalSince1970 * 1000).toLong() - olderThanMs
                val all = lines.mapNotNull<String, StoredEvent> { runCatching { json.decodeFromString<StoredEvent>(it) }.getOrNull() }
                val toKeep = all.filter { it.timestamp > cutoff }
                writeEventsAtomic(toKeep)
                all.size - toKeep.size
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "IosEventStore: Failed to clear old events", e)
                0
            }
        }
    }

    override suspend fun clearAll() = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            writeFileAtomic(eventsFileURL, "")
        }
    }

    override suspend fun getEventCount(): Int = withContext(IosDispatchers.IO) {
        fileLock.withLock {
            readAllLines().size
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun readAllLines(): List<String> {
        val path = eventsFileURL.path ?: return emptyList()
        if (!fileManager.fileExistsAtPath(path)) return emptyList()
        // COORDINATED READ
        val content = IosFileCoordinator.coordinateSync(eventsFileURL, write = false) { safeUrl ->
            NSString.stringWithContentsOfURL(safeUrl, encoding = NSUTF8StringEncoding, error = null)
        } ?: return emptyList()
        return (content as String).lines().filter { it.isNotBlank() }
    }

    private fun checkFreeSpace(url: NSURL): Long {
        val path = url.path ?: return 0L
        val attributes = fileManager.attributesOfFileSystemForPath(path, error = null) ?: return 0L
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L
    }

    private fun shouldPerformCleanup(): Boolean {
        val now = (NSDate().timeIntervalSince1970() * 1000).toLong()
        if (now - lastCleanupTimeMs >= config.cleanupIntervalMs) return true
        val path = eventsFileURL.path ?: return false
        val attrs = fileManager.attributesOfItemAtPath(path, error = null) ?: return false
        return (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L >= config.cleanupFileSizeThresholdBytes
    }

    private suspend fun performCleanupInternal() {
        val lines = readAllLines()
        val all = lines.mapNotNull<String, StoredEvent> { runCatching { json.decodeFromString<StoredEvent>(it) }.getOrNull() }
        val now: Long = (NSDate().timeIntervalSince1970() * 1000).toLong()
        val toKeep = all.filter { event ->
            val age: Long = now - event.timestamp
            if (event.consumed) age <= config.consumedEventRetentionMs 
            else age <= config.unconsumedEventRetentionMs
        }.sortedByDescending { it.timestamp }.take(config.maxEvents)

        if (toKeep.size < all.size) {
            writeEventsAtomic(toKeep)
            Logger.d(LogTags.SCHEDULER, "IosEventStore: Cleanup removed ${all.size - toKeep.size} events")
        }
    }

    private suspend fun writeEventsAtomic(events: List<StoredEvent>) {
        val content = events.joinToString("\n") { json.encodeToString(it) } + if (events.isNotEmpty()) "\n" else ""
        writeFileAtomic(eventsFileURL, content)
    }

    private fun writeFileAtomic(url: NSURL, content: String) {
        IosFileCoordinator.coordinateSync(url, write = true) { safeUrl ->
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                (content as NSString).writeToURL(safeUrl, atomically = true, encoding = NSUTF8StringEncoding, error = errorPtr.ptr)
            }
        }
    }

    private fun ensureDirectoryExists(url: NSURL) {
        if (!fileManager.fileExistsAtPath(url.path ?: "")) {
            // NSFileProtectionCompleteUntilFirstUserAuthentication keeps files accessible to
            // background tasks after first unlock. The OS default (NSFileProtectionComplete)
            // would lock files when screen is off, breaking all background event writes.
            val attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
            fileManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = attributes, error = null)
        }
    }
}

private fun NSURL.safeAppend(component: String): NSURL =
    URLByAppendingPathComponent(component) ?: throw IllegalStateException("Failed URL build")
