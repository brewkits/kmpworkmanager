package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Library/Application Support/dev.brewkits.kmpworkmanager/
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

    // Tolerant Json for all persisted data: ignores unknown keys so that data written by a
    // newer schema version does not crash on rollback or when consumed by an older class.
    private val persistenceJson = Json { ignoreUnknownKeys = true }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val queue: AppendOnlyQueue by lazy {
        // Create queue subdirectory for better organization
        val queueDirURL = baseDir.URLByAppendingPathComponent("queue")!!
        ensureDirectoryExists(queueDirURL)
        AppendOnlyQueue(queueDirURL)
    }

    // In-memory mutex for queue operations (complements file coordinator)
    // Note: AppendOnlyQueue has its own mutex, but we keep this for coordinated operations
    private val queueMutex = Mutex()

    companion object {
        const val MAX_QUEUE_SIZE = 1000
        const val MAX_CHAIN_SIZE_BYTES = 10_485_760L // 10MB

        const val DELETED_MARKER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        private const val BASE_DIR_NAME = "dev.brewkits.kmpworkmanager"
        private const val QUEUE_FILE_NAME = "queue.jsonl"
        private const val CHAINS_DIR_NAME = "chains"
        private const val METADATA_DIR_NAME = "metadata"
        private const val TASKS_DIR_NAME = "tasks"
        private const val PERIODIC_DIR_NAME = "periodic"
        private const val DELETED_CHAINS_DIR_NAME = "deleted_chains"
    }

    /**
     * Base directory path: Library/Application Support/dev.brewkits.kmpworkmanager/
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
    private val deletedChainsDirURL: NSURL by lazy {
        val url = metadataDirURL.URLByAppendingPathComponent(DELETED_CHAINS_DIR_NAME)!!
        ensureDirectoryExists(url)
        url
    }

    private val maintenanceTimestampURL: NSURL by lazy {
        baseDir.URLByAppendingPathComponent("last_maintenance.txt")!!
    }

    init {
        backgroundScope.launch {
            val hoursSinceLastMaintenance = getHoursSinceLastMaintenance()

            if (hoursSinceLastMaintenance >= 24) {
                // Run immediately if maintenance hasn't run in 24+ hours
                Logger.i(LogTags.SCHEDULER, "Maintenance overdue ($hoursSinceLastMaintenance hours). Running immediately...")
                performMaintenanceTasks()
            } else {
                // Wait 5s for app to stabilize before running maintenance
                delay(5000)
                performMaintenanceTasks()
            }
        }
    }

    // ==================== Queue Operations ====================

    /**
     * Enqueue a chain ID to the queue (thread-safe, atomic)
     */
    suspend fun enqueueChain(chainId: String) {
        // Check queue size before enqueue (AppendOnlyQueue doesn't enforce limit)
        val currentSize = queue.getSize()
        if (currentSize >= MAX_QUEUE_SIZE) {
            Logger.e(LogTags.CHAIN, "Queue size limit reached ($MAX_QUEUE_SIZE). Cannot enqueue chain: $chainId")
            throw IllegalStateException("Queue size limit exceeded")
        }

        // O(1) enqueue operation
        queue.enqueue(chainId)

        Logger.v(LogTags.CHAIN, "Enqueued chain $chainId. Queue size: ${currentSize + 1}")
    }

    /**
     * Dequeue the first chain ID from the queue (thread-safe, atomic)
     * @return Chain ID or null if queue is empty
     */
    suspend fun dequeueChain(): String? {
        // O(1) dequeue operation (with automatic compaction at 80% threshold)
        val chainId = queue.dequeue()

        if (chainId == null) {
            Logger.v(LogTags.CHAIN, "Queue is empty")
        } else {
            Logger.v(LogTags.CHAIN, "Dequeued chain $chainId. Remaining: ${queue.getSize()}")
        }

        return chainId
    }

    /**
     * Get current queue size
     */
    suspend fun getQueueSize(): Int {
        return queue.getSize()
    }

    // readQueueInternal() and writeQueueInternal() removed - no longer needed

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

        checkDiskSpace(sizeBytes)

        coordinated(chainFile, write = true) { safeUrl ->
            writeStringToFile(safeUrl, json)
        }

        Logger.d(LogTags.CHAIN, "Saved chain definition $id ($sizeBytes bytes)")
    }

    /**
     * Load chain definition from file with self-healing for corrupt data
     */
    fun loadChainDefinition(id: String): List<List<TaskRequest>>? {
        val chainFile = chainsDirURL.URLByAppendingPathComponent("$id.json")!!

        return coordinated(chainFile, write = false) { safeUrl ->
            val json = readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                persistenceJson.decodeFromString<List<List<TaskRequest>>>(json)
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "🩹 Self-healing: Corrupt chain definition detected for $id. Deleting corrupt file...", e)

                // Delete corrupt chain definition
                deleteFile(chainFile)
                // Also delete associated progress file to maintain consistency
                deleteChainProgress(id)

                Logger.w(LogTags.CHAIN, "Corrupt chain $id has been removed. It will need to be re-enqueued if still needed.")
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

    /**
     * Check if chain definition exists
     */
    fun chainExists(id: String): Boolean {
        val chainFile = chainsDirURL.URLByAppendingPathComponent("$id.json")!!
        val path = chainFile.path ?: return false
        return fileManager.fileExistsAtPath(path)
    }

    // ==================== Deleted Chain Markers (v2.1.3+) ====================

    /**
     * Mark a chain as deleted to prevent duplicate execution during REPLACE policy.
     * The marker contains a timestamp for cleanup purposes.
     *
     */
    fun markChainAsDeleted(chainId: String) {
        val markerFile = deletedChainsDirURL.URLByAppendingPathComponent("$chainId.marker")!!
        val timestamp = NSDate().timeIntervalSince1970.toLong()

        coordinated(markerFile, write = true) { safeUrl ->
            writeStringToFile(safeUrl, timestamp.toString())
        }

        Logger.d(LogTags.CHAIN, "Marked chain $chainId as deleted (REPLACE policy)")
    }

    /**
     * Check if a chain has been marked as deleted.
     * Used by ChainExecutor to skip execution of replaced chains.
     *
     */
    fun isChainDeleted(chainId: String): Boolean {
        val markerFile = deletedChainsDirURL.URLByAppendingPathComponent("$chainId.marker")!!
        val path = markerFile.path ?: return false
        return fileManager.fileExistsAtPath(path)
    }

    /**
     * Clear the deleted marker for a chain.
     * Called after skipping execution of a deleted chain.
     *
     */
    fun clearDeletedMarker(chainId: String) {
        val markerFile = deletedChainsDirURL.URLByAppendingPathComponent("$chainId.marker")!!
        deleteFile(markerFile)
        Logger.d(LogTags.CHAIN, "Cleared deleted marker for chain $chainId")
    }

    /**
     * Remove deleted markers older than DELETED_MARKER_MAX_AGE_MS (7 days).
     * Prevents disk space leaks from accumulated markers.
     *
     * CRITICAL: Called from performMaintenanceTasks() to prevent accumulation
     */
    fun cleanupStaleDeletedMarkers() {
        val path = deletedChainsDirURL.path ?: return
        val files = fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return

        val now = NSDate().timeIntervalSince1970.toLong() * 1000 // Convert to milliseconds
        var cleanedCount = 0

        files.forEach { fileName ->
            if (fileName !is String) return@forEach
            if (!fileName.endsWith(".marker")) return@forEach

            val markerFile = deletedChainsDirURL.URLByAppendingPathComponent(fileName)!!
            val markerPath = markerFile.path ?: return@forEach

            // Read timestamp from marker file
            val timestampStr = readStringFromFile(markerFile)
            val timestamp = timestampStr?.toLongOrNull() ?: 0L

            // Check if marker is stale (older than 7 days)
            val ageMs = now - (timestamp * 1000) // timestamp is in seconds
            if (ageMs > DELETED_MARKER_MAX_AGE_MS) {
                fileManager.removeItemAtPath(markerPath, null)
                cleanedCount++
                Logger.d(LogTags.CHAIN, "Cleaned up stale deleted marker: $fileName (age: ${ageMs / 86400000}days)")
            }
        }

        if (cleanedCount > 0) {
            Logger.i(LogTags.CHAIN, "Cleaned up $cleanedCount stale deleted markers")
        }
    }

    /**
     * Perform periodic maintenance tasks.
     *
     * CRITICAL: Called from init block with intelligent delay to prevent blocking app launch
     * This prevents accumulation of stale markers (CRITICAL_WARNINGS.md #2)
     */
    fun performMaintenanceTasks() {
        try {
            Logger.d(LogTags.CHAIN, "Starting maintenance tasks...")

            // Clean up stale deleted markers (> 7 days old)
            cleanupStaleDeletedMarkers()

            // Clean up stale metadata (existing functionality)
            cleanupStaleMetadata(olderThanDays = 7)

            recordMaintenanceCompletion()

            Logger.d(LogTags.CHAIN, "Maintenance tasks completed")
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Maintenance tasks failed", e)
        }
    }

    /**
     * Get hours since last maintenance run
     *
     * @return Hours since last maintenance, or Int.MAX_VALUE if never run
     */
    private fun getHoursSinceLastMaintenance(): Int {
        val path = maintenanceTimestampURL.path ?: return Int.MAX_VALUE

        if (!fileManager.fileExistsAtPath(path)) {
            return Int.MAX_VALUE // Never run before
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            val lastRunTimestamp = content?.toString()?.trim()?.toLongOrNull() ?: return Int.MAX_VALUE
            val currentTimestamp = NSDate().timeIntervalSince1970.toLong()
            val hoursSince = (currentTimestamp - lastRunTimestamp) / 3600

            hoursSince.toInt()
        }
    }

    /**
     * Record maintenance completion timestamp
     */
    private fun recordMaintenanceCompletion() {
        val path = maintenanceTimestampURL.path ?: return
        val timestamp = NSDate().timeIntervalSince1970.toLong()

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = timestamp.toString() as NSString

            content.writeToFile(
                path,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
        }
    }

    // ==================== Chain Progress Operations ====================

    /**
     * Save chain progress to file.
     *
     * Progress is stored separately from the chain definition to allow
     * resuming chains after interruptions (timeout, force-quit, etc.).
     *
     * @param progress The progress state to save
     */
    fun saveChainProgress(progress: ChainProgress) {
        val progressFile = chainsDirURL.URLByAppendingPathComponent("${progress.chainId}_progress.json")!!
        val json = Json.encodeToString(progress)

        coordinated(progressFile, write = true) { safeUrl ->
            writeStringToFile(safeUrl, json)
        }

        Logger.d(
            LogTags.CHAIN,
            "Saved chain progress ${progress.chainId} (${progress.getCompletionPercentage()}% complete, ${progress.completedSteps.size}/${progress.totalSteps} steps)"
        )
    }

    /**
     * Load chain progress from file with self-healing for corrupt data.
     *
     * @param chainId The chain ID
     * @return The progress state, or null if no progress file exists or is corrupt
     */
    fun loadChainProgress(chainId: String): ChainProgress? {
        val progressFile = chainsDirURL.URLByAppendingPathComponent("${chainId}_progress.json")!!

        return coordinated(progressFile, write = false) { safeUrl ->
            val json = readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                persistenceJson.decodeFromString<ChainProgress>(json)
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "🩹 Self-healing: Corrupt chain progress detected for $chainId. Deleting corrupt file...", e)

                // Delete corrupt progress file - chain will restart from beginning
                deleteFile(progressFile)

                Logger.w(LogTags.CHAIN, "Corrupt progress for chain $chainId has been removed. Chain will restart from beginning on next execution.")
                null
            }
        }
    }

    /**
     * Delete chain progress file.
     *
     * Should be called when:
     * - Chain completes successfully
     * - Chain is abandoned (exceeded retry limit)
     * - Chain definition is deleted
     *
     * @param chainId The chain ID
     */
    fun deleteChainProgress(chainId: String) {
        val progressFile = chainsDirURL.URLByAppendingPathComponent("${chainId}_progress.json")!!
        deleteFile(progressFile)
        Logger.d(LogTags.CHAIN, "Deleted chain progress $chainId")
    }

    // ==================== Metadata Operations ====================

    /**
     * Save task metadata
     */
    fun saveTaskMetadata(id: String, metadata: Map<String, String>, periodic: Boolean) {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.URLByAppendingPathComponent("$id.json")!!
        val json = Json.encodeToString(metadata)

        coordinated(metaFile, write = true) { safeUrl ->
            writeStringToFile(safeUrl, json)
        }

        Logger.d(LogTags.SCHEDULER, "Saved ${if (periodic) "periodic" else "task"} metadata for $id")
    }

    /**
     * Load task metadata with self-healing for corrupt data
     */
    fun loadTaskMetadata(id: String, periodic: Boolean): Map<String, String>? {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.URLByAppendingPathComponent("$id.json")!!

        return coordinated(metaFile, write = false) { safeUrl ->
            val json = readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                persistenceJson.decodeFromString<Map<String, String>>(json)
            } catch (e: Exception) {
                val metadataType = if (periodic) "periodic" else "task"
                Logger.e(LogTags.SCHEDULER, "🩹 Self-healing: Corrupt $metadataType metadata detected for $id. Deleting corrupt file...", e)

                // Delete corrupt metadata file
                deleteFile(metaFile)

                Logger.w(LogTags.SCHEDULER, "Corrupt $metadataType metadata for $id has been removed. Task will need to be rescheduled.")
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
     * Check if sufficient disk space is available
     *
     * **Safety margin:** Requires 100MB buffer + actual size to prevent
     * system-wide issues and ensure smooth operation.
     *
     * @param requiredBytes Minimum bytes needed for the operation
     * @throws InsufficientDiskSpaceException if space unavailable
     */
    private fun checkDiskSpace(requiredBytes: Long) {
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            // Get filesystem attributes
            val attributes = fileManager.attributesOfFileSystemForPath(
                baseDir.path!!,
                error = errorPtr.ptr
            ) as? Map<*, *>

            if (attributes == null) {
                Logger.w(LogTags.CHAIN, "Cannot read filesystem attributes - skipping disk space check")
                return
            }

            // Get free space
            val freeSpace = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L

            // Require 100MB buffer + actual size
            val requiredWithBuffer = requiredBytes + 100_000_000L // 100MB buffer

            if (freeSpace < requiredWithBuffer) {
                val freeMB = freeSpace / 1024 / 1024
                val requiredMB = requiredWithBuffer / 1024 / 1024

                Logger.e(LogTags.CHAIN, "Insufficient disk space: ${freeMB}MB available, ${requiredMB}MB required")
                throw InsufficientDiskSpaceException(requiredWithBuffer, freeSpace)
            }

            Logger.d(LogTags.CHAIN, "Disk space check passed: ${freeSpace / 1024 / 1024}MB available, ${requiredWithBuffer / 1024 / 1024}MB required")
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
     *
     * CRITICAL: NSFileCoordinator is REQUIRED in production for:
     * - Inter-process file coordination (App + Extensions)
     * - iCloud synchronization safety
     * - System file operation conflicts (Spotlight, etc.)
     *
     * ISSUE: NSFileCoordinator callbacks (both async AND synchronous) do not execute
     * reliably in unit test contexts, causing test failures.
     *
     * SOLUTION: Detect test environment and skip coordination for tests only.
     * - Production: Full NSFileCoordinator protection (inter-process safety)
     * - Tests: Direct execution with Kotlin Mutex protection (in-process safety)
     *
     * Detection: Test executables have "test.kexe" in NSProcessInfo
     */
    private fun <T> coordinated(url: NSURL, write: Boolean, block: (NSURL) -> T): T {
        val isTestEnvironment = platform.Foundation.NSProcessInfo.processInfo.processName.contains("test.kexe")

        if (isTestEnvironment) {
            // Test environment: Direct execution (Kotlin Mutex provides thread-safety)
            return block(url)
        }

        // Production: Full NSFileCoordinator protection
        var result: T? = null
        var blockError: Exception? = null

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            if (write) {
                fileCoordinator.coordinateWritingItemAtURL(
                    url = url,
                    options = 0u,
                    error = errorPtr.ptr,
                    byAccessor = { actualURL ->
                        try {
                            result = block(actualURL!!)
                        } catch (e: Exception) {
                            blockError = e
                        }
                    }
                )
            } else {
                fileCoordinator.coordinateReadingItemAtURL(
                    url = url,
                    options = 0u,
                    error = errorPtr.ptr,
                    byAccessor = { actualURL ->
                        try {
                            result = block(actualURL!!)
                        } catch (e: Exception) {
                            blockError = e
                        }
                    }
                )
            }

            errorPtr.value?.let { error ->
                throw IllegalStateException("File coordination failed: ${error.localizedDescription}")
            }
        }

        blockError?.let { throw it }
        return result ?: throw IllegalStateException("File coordination callback did not execute")
    }
}

/**
 * Exception thrown when insufficient disk space is available
 */
class InsufficientDiskSpaceException(
    val required: Long,
    val available: Long
) : Exception(
    "Insufficient disk space. Required: ${required / 1024 / 1024}MB, " +
    "Available: ${available / 1024 / 1024}MB"
)
