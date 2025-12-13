package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.TaskRequest
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * Thread-safe file-based storage for iOS task data using native iOS APIs.
 * Replaces NSUserDefaults to fix race conditions and improve performance.
 *
 * Features:
 * - Atomic file operations using NSFileCoordinator
 * - Bounded queue size (max 1000 chains)
 * - Chain size limits (max 10MB per chain)
 * - Automatic garbage collection
 * - No third-party dependencies (pure iOS APIs)
 *
 * File Structure:
 * ```
 * Library/Application Support/io.kmp.taskmanager/
 * ├── queue.jsonl              # Chain queue (append-only)
 * ├── chains/
 * │   ├── <uuid1>.json         # Chain definitions
 * │   └── <uuid2>.json
 * └── metadata/
 *     ├── tasks/
 *     │   └── <taskId>.json    # Task metadata
 *     └── periodic/
 *         └── <taskId>.json    # Periodic metadata
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileStorage {

    private val fileManager = NSFileManager.defaultManager
    private val fileCoordinator = NSFileCoordinator(filePresenter = null)

    // In-memory mutex for queue operations (complements file coordinator)
    private val queueMutex = Mutex()

    companion object {
        const val MAX_QUEUE_SIZE = 1000
        const val MAX_CHAIN_SIZE_BYTES = 10_485_760L // 10MB

        private const val BASE_DIR_NAME = "io.kmp.taskmanager"
        private const val QUEUE_FILE_NAME = "queue.jsonl"
        private const val CHAINS_DIR_NAME = "chains"
        private const val METADATA_DIR_NAME = "metadata"
        private const val TASKS_DIR_NAME = "tasks"
        private const val PERIODIC_DIR_NAME = "periodic"
    }

    /**
     * Base directory path: Library/Application Support/io.kmp.taskmanager/
     */
    private val baseDir: NSURL by lazy {
        val urls = fileManager.URLsForDirectory(
            NSApplicationSupportDirectory,
            NSUserDomainMask
        ) as List<*>
        val appSupportDir = urls.firstOrNull() as? NSURL
            ?: throw IllegalStateException("Could not locate Application Support directory")

        val basePath = appSupportDir.URLByAppendingPathComponent(BASE_DIR_NAME)!!
        ensureDirectoryExists(basePath)

        Logger.d(LogTags.SCHEDULER, "IosFileStorage initialized at: ${basePath.path}")
        basePath
    }

    private val queueFileURL: NSURL by lazy { baseDir.URLByAppendingPathComponent(QUEUE_FILE_NAME)!! }
    private val chainsDirURL: NSURL by lazy {
        val url = baseDir.URLByAppendingPathComponent(CHAINS_DIR_NAME)!!
        ensureDirectoryExists(url)
        url
    }
    private val metadataDirURL: NSURL by lazy {
        val url = baseDir.URLByAppendingPathComponent(METADATA_DIR_NAME)!!
        ensureDirectoryExists(url)
        url
    }
    private val tasksDirURL: NSURL by lazy {
        val url = metadataDirURL.URLByAppendingPathComponent(TASKS_DIR_NAME)!!
        ensureDirectoryExists(url)
        url
    }
    private val periodicDirURL: NSURL by lazy {
        val url = metadataDirURL.URLByAppendingPathComponent(PERIODIC_DIR_NAME)!!
        ensureDirectoryExists(url)
        url
    }

    // ==================== Queue Operations ====================

    /**
     * Enqueue a chain ID to the queue (thread-safe, atomic)
     */
    suspend fun enqueueChain(chainId: String) {
        queueMutex.withLock {
            coordinated(queueFileURL, write = true) {
                val currentQueue = readQueueInternal()

                // Validate queue size limit
                if (currentQueue.size >= MAX_QUEUE_SIZE) {
                    Logger.e(LogTags.CHAIN, "Queue size limit reached ($MAX_QUEUE_SIZE). Cannot enqueue chain: $chainId")
                    throw IllegalStateException("Queue size limit exceeded")
                }

                // Append to queue
                val updatedQueue = currentQueue + chainId
                writeQueueInternal(updatedQueue)

                Logger.d(LogTags.CHAIN, "Enqueued chain $chainId. Queue size: ${updatedQueue.size}")
            }
        }
    }

    /**
     * Dequeue the first chain ID from the queue (thread-safe, atomic)
     * @return Chain ID or null if queue is empty
     */
    suspend fun dequeueChain(): String? {
        return queueMutex.withLock {
            coordinated(queueFileURL, write = true) {
                val currentQueue = readQueueInternal()

                if (currentQueue.isEmpty()) {
                    Logger.d(LogTags.CHAIN, "Queue is empty")
                    return@coordinated null
                }

                val chainId = currentQueue.first()
                val updatedQueue = currentQueue.drop(1)
                writeQueueInternal(updatedQueue)

                Logger.d(LogTags.CHAIN, "Dequeued chain $chainId. Remaining: ${updatedQueue.size}")
                chainId
            }
        }
    }

    /**
     * Get current queue size
     */
    fun getQueueSize(): Int {
        return coordinated(queueFileURL, write = false) {
            readQueueInternal().size
        }
    }

    /**
     * Read queue from file (internal, must be called within coordinated block)
     */
    private fun readQueueInternal(): List<String> {
        val path = queueFileURL.path ?: return emptyList()

        if (!fileManager.fileExistsAtPath(path)) {
            return emptyList()
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            if (content == null) {
                Logger.w(LogTags.CHAIN, "Failed to read queue file: ${errorPtr.value?.localizedDescription}")
                return emptyList()
            }

            // Parse JSONL format (one ID per line)
            content.split("\n")
                .filter { it.isNotBlank() }
        }
    }

    /**
     * Write queue to file (internal, must be called within coordinated block)
     */
    private fun writeQueueInternal(queue: List<String>) {
        val path = queueFileURL.path ?: return
        val content = queue.joinToString("\n") + (if (queue.isNotEmpty()) "\n" else "")

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val nsString = content as NSString

            val success = nsString.writeToFile(
                path,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            if (!success) {
                Logger.e(LogTags.CHAIN, "Failed to write queue file: ${errorPtr.value?.localizedDescription}")
                throw IllegalStateException("Failed to write queue file")
            }
        }
    }

    // ==================== Chain Definition Operations ====================

    /**
     * Save chain definition to file
     */
    fun saveChainDefinition(id: String, steps: List<List<TaskRequest>>) {
        val chainFile = chainsDirURL.URLByAppendingPathComponent("$id.json")!!
        val json = Json.encodeToString(steps)

        // Validate chain size
        val sizeBytes = json.length.toLong()
        if (sizeBytes > MAX_CHAIN_SIZE_BYTES) {
            Logger.e(LogTags.CHAIN, "Chain $id exceeds size limit: $sizeBytes bytes (max: $MAX_CHAIN_SIZE_BYTES)")
            throw IllegalStateException("Chain size exceeds limit")
        }

        coordinated(chainFile, write = true) {
            writeStringToFile(chainFile, json)
        }

        Logger.d(LogTags.CHAIN, "Saved chain definition $id ($sizeBytes bytes)")
    }

    /**
     * Load chain definition from file
     */
    fun loadChainDefinition(id: String): List<List<TaskRequest>>? {
        val chainFile = chainsDirURL.URLByAppendingPathComponent("$id.json")!!

        return coordinated(chainFile, write = false) {
            val json = readStringFromFile(chainFile) ?: return@coordinated null

            try {
                Json.decodeFromString<List<List<TaskRequest>>>(json)
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Failed to deserialize chain $id", e)
                null
            }
        }
    }

    /**
     * Delete chain definition
     */
    fun deleteChainDefinition(id: String) {
        val chainFile = chainsDirURL.URLByAppendingPathComponent("$id.json")!!
        deleteFile(chainFile)
        Logger.d(LogTags.CHAIN, "Deleted chain definition $id")
    }

    // ==================== Metadata Operations ====================

    /**
     * Save task metadata
     */
    fun saveTaskMetadata(id: String, metadata: Map<String, String>, periodic: Boolean) {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.URLByAppendingPathComponent("$id.json")!!
        val json = Json.encodeToString(metadata)

        coordinated(metaFile, write = true) {
            writeStringToFile(metaFile, json)
        }

        Logger.d(LogTags.SCHEDULER, "Saved ${if (periodic) "periodic" else "task"} metadata for $id")
    }

    /**
     * Load task metadata
     */
    fun loadTaskMetadata(id: String, periodic: Boolean): Map<String, String>? {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.URLByAppendingPathComponent("$id.json")!!

        return coordinated(metaFile, write = false) {
            val json = readStringFromFile(metaFile) ?: return@coordinated null

            try {
                Json.decodeFromString<Map<String, String>>(json)
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Failed to deserialize metadata for $id", e)
                null
            }
        }
    }

    /**
     * Delete task metadata
     */
    fun deleteTaskMetadata(id: String, periodic: Boolean) {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.URLByAppendingPathComponent("$id.json")!!
        deleteFile(metaFile)
        Logger.d(LogTags.SCHEDULER, "Deleted ${if (periodic) "periodic" else "task"} metadata for $id")
    }

    /**
     * Cleanup stale metadata older than specified days
     */
    fun cleanupStaleMetadata(olderThanDays: Int = 7) {
        val cutoffDate = NSDate().dateByAddingTimeInterval(-olderThanDays.toDouble() * 86400)

        listOf(tasksDirURL, periodicDirURL).forEach { dir ->
            val path = dir.path ?: return@forEach
            val files = fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return@forEach

            files.forEach { fileName ->
                val filePath = "$path/$fileName"
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val attrs = fileManager.attributesOfItemAtPath(filePath, errorPtr.ptr)

                    val modDate = attrs?.get(NSFileModificationDate) as? NSDate
                    if (modDate != null && modDate.compare(cutoffDate) == NSOrderedAscending) {
                        fileManager.removeItemAtPath(filePath, null)
                        Logger.d(LogTags.SCHEDULER, "Cleaned up stale metadata: $fileName")
                    }
                }
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Ensure directory exists, create if not
     */
    private fun ensureDirectoryExists(url: NSURL) {
        val path = url.path ?: return

        if (!fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = errorPtr.ptr
                )

                if (errorPtr.value != null) {
                    throw IllegalStateException("Failed to create directory: ${errorPtr.value?.localizedDescription}")
                }
            }
        }
    }

    /**
     * Read string from file
     */
    private fun readStringFromFile(url: NSURL): String? {
        val path = url.path ?: return null

        if (!fileManager.fileExistsAtPath(path)) {
            return null
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
        }
    }

    /**
     * Write string to file atomically
     */
    private fun writeStringToFile(url: NSURL, content: String) {
        val path = url.path ?: return

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val nsString = content as NSString

            val success = nsString.writeToFile(
                path,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            if (!success) {
                throw IllegalStateException("Failed to write file: ${errorPtr.value?.localizedDescription}")
            }
        }
    }

    /**
     * Delete file if exists
     */
    private fun deleteFile(url: NSURL) {
        val path = url.path ?: return

        if (fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.removeItemAtPath(path, errorPtr.ptr)

                if (errorPtr.value != null) {
                    Logger.w(LogTags.SCHEDULER, "Failed to delete file: ${errorPtr.value?.localizedDescription}")
                }
            }
        }
    }

    /**
     * Execute block with file coordination for atomic operations
     */
    private fun <T> coordinated(url: NSURL, write: Boolean, block: () -> T): T {
        var result: T? = null
        var error: Exception? = null

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val intent = if (write) {
                NSFileAccessIntent.writingIntentWithURL(url, options = 0u)
            } else {
                NSFileAccessIntent.readingIntentWithURL(url, options = 0u)
            }

            fileCoordinator.coordinateAccessWithIntents(
                listOf(intent),
                queue = NSOperationQueue.mainQueue,
                byAccessor = { err ->
                    if (err == null) {
                        try {
                            result = block()
                        } catch (e: Exception) {
                            error = e
                        }
                    } else {
                        error = IllegalStateException("File coordination failed: ${err.localizedDescription}")
                    }
                }
            )
        }

        error?.let { throw it }
        return result!!
    }
}
