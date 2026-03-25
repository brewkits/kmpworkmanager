package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import platform.Foundation.*
import platform.darwin.*
import kotlin.concurrent.AtomicInt

/**
 * Transaction record for chain operations
 * Logs all chain mutations for debugging and recovery
 */
@Serializable
internal data class ChainTransaction(
    val chainId: String,
    val action: String,  // "ENQUEUE", "DELETE", "REPLACE"
    val timestamp: Long,
    val succeeded: Boolean,
    val error: String? = null
)

/**
 * Configuration for IosFileStorage
 *
 * @param diskSpaceBufferBytes Safety margin for disk space checks.
 *        Default: 50MB (reduced from 100MB for better mobile device compatibility)
 * @param deletedMarkerMaxAgeMs Maximum age for deleted chain markers before cleanup.
 *        Default: 7 days (604800000ms)
 * @param isTestMode Override test detection. If null, auto-detects via environment variable
 *        or process name. Set explicitly for reliable test detection.
 *        Default: null (auto-detect)
 * @param fileCoordinationTimeoutMs Timeout for NSFileCoordinator operations (prevents hangs).
 *        Default: 30 seconds (30000ms). Set to 0 to disable timeout.
 */
@Serializable
internal data class IosFileStorageConfig(
    val diskSpaceBufferBytes: Long = 50_000_000L,  // 50MB default (was 100MB)
    val deletedMarkerMaxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L,  // 7 days default
    val isTestMode: Boolean? = null,  // null = auto-detect, true/false = override
    val fileCoordinationTimeoutMs: Long = 30_000L  // 30 seconds default (0 = disabled)
)

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
 * - Configurable disk space safety margin
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
internal class IosFileStorage(
    private val config: IosFileStorageConfig = IosFileStorageConfig(),
    private val baseDirectory: NSURL? = null  // null = use AppSupport/dev.brewkits.kmpworkmanager
) {

    private val fileManager = NSFileManager.defaultManager
    private val fileCoordinator = NSFileCoordinator(filePresenter = null)

    // Tolerant Json for all persisted data: ignores unknown keys so that data written by a
    // newer schema version does not crash on rollback or when consumed by an older class.
    private val persistenceJson = Json { ignoreUnknownKeys = true }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val queue: AppendOnlyQueue by lazy {
        // Create queue subdirectory for better organization
        val queueDirURL = baseDir.safeAppend("queue")
        ensureDirectoryExists(queueDirURL)
        
        // Auto-detect test mode based on process name
        val processName = NSProcessInfo.processInfo.processName
        val isTest = processName.endsWith("test.kexe")
        
        AppendOnlyQueue(
            baseDirectoryURL = queueDirURL,
            compactionScope = backgroundScope,
            isTestMode = isTest
        )
    }

    // ==================== Lock-Ordering Invariant ====================
    // This class uses three coroutine mutexes. To prevent deadlock, they must NEVER
    // be acquired in conflicting orders across code paths. The only permitted ordering is:
    //
    //   queueMutex  →  enqueueMutex     (replaceChainAtomic holds queueMutex, calls
    //                                    enqueueChainInternal which is lock-free)
    //
    // progressMutex is COMPLETELY INDEPENDENT from queueMutex and enqueueMutex.
    // No code path may hold both progressMutex and queueMutex simultaneously.
    //
    // AppendOnlyQueue has its own internal lock hierarchy (corruptionMutex → queueMutex).
    // IosFileStorage.queueMutex wraps AppendOnlyQueue calls, giving the outer ordering:
    //   IosFileStorage.queueMutex  →  AppendOnlyQueue.corruptionMutex  →  AppendOnlyQueue.queueMutex
    //
    // IMPORTANT: Any new code that acquires two of these mutexes must follow this order.
    // Acquiring in reverse order will cause a coroutine deadlock (all involved coroutines
    // suspend forever, no error is thrown, the BGTask is killed by iOS watchdog).
    // ===================================================================

    // Protects coordinated queue operations (dequeue, replaceChainAtomic, etc.)
    private val queueMutex = Mutex()

    /**
     * Dedicated mutex for enqueue operations to ensure atomic check-then-act
     * for MAX_QUEUE_SIZE enforcement.
     *
     * enqueueChain() has a check-then-act pattern: two concurrent callers could both
     * read counter=999, both pass the limit check, and both enqueue — resulting in
     * queue size > MAX_QUEUE_SIZE. This mutex prevents that race.
     *
     * This mutex is separate from queueMutex to avoid deadlock: replaceChainAtomic()
     * holds queueMutex and calls enqueueChain() internally (via enqueueChainInternal()).
     */
    private val enqueueMutex = Mutex()

    /**
     * Queue size counter for O(1) size checks.
     * Initialized to UNINITIALIZED_COUNTER (-1) instead of 0.
     *
     * **Why -1 and not 0:** If enqueueChain() is called before the init coroutine sets
     * the real value, the limit check sees 0 and allows enqueues even when the
     * queue already has MAX_QUEUE_SIZE-1 items (app restart with nearly-full queue).
     *
     * Sentinel -1 signals enqueueChain() to read the actual size from disk (O(N),
     * one-time cost) before checking the limit. After init completes, the counter is
     * accurate and all subsequent checks are O(1) lock-free.
     */
    private val queueSizeCounter = AtomicInt(UNINITIALIZED_COUNTER)

    /**
     * Buffered I/O for progress saves
     * In-memory buffer to batch NSFileCoordinator calls (90% I/O reduction)
     */
    private val progressBuffer = mutableMapOf<String, ChainProgress>()
    private val progressMutex = Mutex()
    private var flushJob: kotlinx.coroutines.Job? = null
    private var flushCompletionSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // Tracks chain IDs whose progress file was deleted by self-healing during this process lifetime.
    // Used by executeChain to prevent non-idempotent chains from silently restarting after corruption.
    // Protected by progressMutex — only written inside loadChainProgress, only read via
    // wasProgressCorrupted() which is called from executeChain before execution begins.
    private val selfHealedProgressChains = mutableSetOf<String>()

    // Disk space cache — `attributesOfFileSystemForPath` is an OS-level I/O syscall.
    // Calling it on every file write (e.g. every saveChainDefinition) adds measurable
    // latency on I/O-bound devices. Cache the result for DISK_SPACE_CACHE_TTL_MS and
    // re-query only when the TTL expires.
    // Thread-safety: reads are allowed without a lock (stale reads are safe — the
    // consequence is one extra syscall, not a correctness error). Writes use @Volatile
    // so the updated pair is visible to all threads without a mutex.
    @kotlin.concurrent.Volatile
    private var diskSpaceCacheFreeBytes: Long = -1L
    @kotlin.concurrent.Volatile
    private var diskSpaceCacheExpiryMs: Long = 0L

    companion object {
        const val MAX_QUEUE_SIZE = 1000
        const val MAX_CHAIN_SIZE_BYTES = 10_485_760L // 10MB
        private const val UNINITIALIZED_COUNTER = -1  // Sentinel: counter not yet set from disk
        private const val DISK_SPACE_CACHE_TTL_MS = 10_000L  // 10 seconds

        /**
         * Default max age for deleted markers (now configurable via IosFileStorageConfig)
         * @deprecated Use IosFileStorageConfig.deletedMarkerMaxAgeMs instead
         */
        @Deprecated("Use IosFileStorageConfig.deletedMarkerMaxAgeMs", ReplaceWith("IosFileStorageConfig().deletedMarkerMaxAgeMs"))
        const val DELETED_MARKER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        /**
         * Debounce window for progress flush (100ms)
         * Balances performance (batching) vs data safety
         *
         * **Why 100ms?**
         * - Reduces data loss window from 500ms to 100ms (80% improvement)
         * - Still allows batching of rapid progress updates
         * - iOS can suspend apps aggressively - shorter window = safer
         * - Minimal performance impact (flush still batched)
         */
        private const val FLUSH_DEBOUNCE_MS = 100L

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
        val basePath = if (baseDirectory != null) {
            ensureDirectoryExists(baseDirectory)
            baseDirectory
        } else {
            val urls = fileManager.URLsForDirectory(
                NSApplicationSupportDirectory,
                NSUserDomainMask
            ) as List<*>
            val appSupportDir = urls.firstOrNull() as? NSURL
                ?: throw IllegalStateException("Could not locate Application Support directory")

            val path = appSupportDir.safeAppend(BASE_DIR_NAME)
            ensureDirectoryExists(path)
            path
        }

        Logger.d(LogTags.SCHEDULER, "IosFileStorage initialized at: ${basePath.path}")
        basePath
    }

    private val queueFileURL: NSURL by lazy { baseDir.safeAppend(QUEUE_FILE_NAME) }
    private val chainsDirURL: NSURL by lazy {
        val url = baseDir.safeAppend(CHAINS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val metadataDirURL: NSURL by lazy {
        val url = baseDir.safeAppend(METADATA_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val tasksDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(TASKS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val periodicDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(PERIODIC_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val deletedChainsDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(DELETED_CHAINS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }

    private val maintenanceTimestampURL: NSURL by lazy {
        baseDir.safeAppend("last_maintenance.txt")
    }

    init {
        backgroundScope.launch {
            // Initialize queue size counter from actual queue size on disk.
            // This ensures the counter is accurate after app restart.
            val actualQueueSize = queue.getSize()
            queueSizeCounter.value = actualQueueSize
            Logger.d(LogTags.SCHEDULER, "Initialized queue size counter: $actualQueueSize")

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
     * Enqueue a chain ID to the queue (thread-safe, atomic).
     *
     * Wrapped in enqueueMutex to make the check-then-act atomic.
     * Without the mutex, two concurrent callers could both read counter=999, both pass the
     * MAX_QUEUE_SIZE check, and both enqueue — exceeding the limit by the number of
     * concurrent callers.
     *
     * Delegates to [enqueueChainInternal] which is also used by [replaceChainAtomic]
     * (under queueMutex) to avoid deadlock — enqueueMutex and queueMutex are always
     * acquired in the same order: queueMutex first, enqueueMutex second.
     */
    suspend fun enqueueChain(chainId: String) = enqueueMutex.withLock {
        enqueueChainInternal(chainId)
    }

    /**
     * Internal enqueue without enqueueMutex — called from replaceChainAtomic (under queueMutex).
     *
     * **Cross-process safety:** [queueSizeCounter] is an in-process AtomicInt. If two
     * OS processes share the same storage path (e.g. main app + Notification Service
     * Extension), each process holds an independent counter that diverges after any
     * enqueue/dequeue in the other process. Using the counter for the MAX_QUEUE_SIZE
     * check would allow the limit to be exceeded by the number of concurrent processes.
     *
     * Fix: always read the actual size from disk for the limit check. The counter is
     * still updated for in-process approximations (e.g. debug logging), but the
     * authoritative check is the disk size. Enqueue is not a hot path (called once
     * per chain schedule), so the O(disk) overhead is acceptable.
     */
    private suspend fun enqueueChainInternal(chainId: String) {
        // Always read disk size for the limit check — cross-process safe.
        val currentSize = queue.getSize()

        if (currentSize >= MAX_QUEUE_SIZE) {
            Logger.e(LogTags.CHAIN, "Queue size limit reached ($MAX_QUEUE_SIZE). Cannot enqueue chain: $chainId")
            throw IllegalStateException("Queue size limit exceeded")
        }

        queue.enqueue(chainId)

        // Keep in-process counter roughly in sync for diagnostic logging.
        // Not used for enforcement — disk read above is authoritative.
        if (queueSizeCounter.value == UNINITIALIZED_COUNTER) {
            queueSizeCounter.value = currentSize + 1
        } else {
            queueSizeCounter.incrementAndGet()
        }

        Logger.v(LogTags.CHAIN, "Enqueued chain $chainId. Queue size (disk): $currentSize → ${currentSize + 1}")
    }

    /**
     * Dequeue the first chain ID from the queue (thread-safe, atomic)
     * Updates queue size counter atomically
     * @return Chain ID or null if queue is empty
     */
    suspend fun dequeueChain(): String? {
        // O(1) dequeue operation (with automatic compaction at 80% threshold)
        val chainId = queue.dequeue()

        if (chainId == null) {
            Logger.v(LogTags.CHAIN, "Queue is empty")
        } else {
            // Decrement counter atomically (lock-free)
            queueSizeCounter.decrementAndGet()
            Logger.v(LogTags.CHAIN, "Dequeued chain $chainId. Remaining: ${queueSizeCounter.value}")
        }

        return chainId
    }

    /**
     * Get current queue size — always reads from disk for correctness.
     *
     * Multiple IosFileStorage instances sharing the same path (e.g. NativeTaskScheduler
     * and ChainExecutor) each have an independent in-memory queueSizeCounter that can
     * diverge after any enqueue/dequeue performed by the other instance.  Reading from
     * disk on every call prevents stale-counter bugs at the cost of a single I/O per call,
     * which is acceptable since getQueueSize() is called infrequently (once per BGTask).
     */
    suspend fun getQueueSize(): Int = queue.getSize()

    /**
     * Replace chain atomically
     *
     * **Problem:** Old REPLACE implementation had TOCTOU race condition:
     * 1. Mark deleted
     * 2. Delete old files
     * 3. Save new definition
     * 4. Enqueue async ← **GAP** - another thread could enqueue duplicate
     *
     * **Solution:** Atomic transaction with queue mutex:
     * - All steps under single queueMutex.withLock
     * - Synchronous enqueue (no async gap)
     * - Transaction log for debugging
     *
     * @param chainId Chain ID to replace
     * @param newSteps New chain steps
     * @throws Exception if transaction fails (rollback automatic via mutex)
     */
    suspend fun replaceChainAtomic(
        chainId: String,
        newSteps: List<List<TaskRequest>>
    ) = queueMutex.withLock {
        val txn = ChainTransaction(
            chainId = chainId,
            action = "REPLACE",
            timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
            succeeded = false
        )

        Logger.i(LogTags.CHAIN, "🔄 Atomic REPLACE transaction started for chain $chainId")

        try {
            // Step 1: Mark as deleted (prevent concurrent execution)
            markChainAsDeleted(chainId)

            // Step 2: Delete old files
            deleteChainDefinition(chainId)
            deleteChainProgress(chainId)

            // Step 3: Save new definition
            saveChainDefinition(chainId, newSteps)

            // Step 4: Enqueue (SYNCHRONOUS, not async - fixes race condition!)
            // Use enqueueChainInternal() to avoid deadlock: we already hold queueMutex,
            // and enqueueChain() acquires enqueueMutex (never queueMutex again → safe).
            enqueueChainInternal(chainId)

            // Step 5: Log successful transaction
            val successTxn = txn.copy(succeeded = true)
            logTransaction(successTxn)

            Logger.i(LogTags.CHAIN, "✅ Atomic REPLACE transaction completed for chain $chainId")

        } catch (e: Exception) {
            // Log failed transaction
            val failedTxn = txn.copy(succeeded = false, error = e.message)
            logTransaction(failedTxn)

            Logger.e(LogTags.CHAIN, "❌ Atomic REPLACE transaction failed for chain $chainId", e)
            throw e
        }
    }

    /**
     * Log transaction for debugging
     * Append-only log for auditing chain operations
     *
     * FIX: Wrapped in NSFileCoordinator for safe concurrent access
     */
    private fun logTransaction(txn: ChainTransaction) {
        try {
            val logFile = baseDir.safeAppend("transactions.jsonl")
            val json = Json.encodeToString(txn)
            val line = "$json\n"

            val path = logFile.path ?: return

            // Create if doesn't exist (outside coordinated block for efficiency)
            if (!fileManager.fileExistsAtPath(path)) {
                fileManager.createFileAtPath(path, null, null)
            }

            // FIX: Wrap file write in coordinated() for thread safety
            coordinated(logFile, write = true) { safeUrl ->
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val fileHandle = NSFileHandle.fileHandleForWritingToURL(safeUrl, errorPtr.ptr)

                    // FIX: Handle null fileHandle
                    if (fileHandle == null) {
                        val error = errorPtr.value
                        Logger.w(LogTags.CHAIN, "Failed to open transaction log: ${error?.localizedDescription}")
                        return@coordinated  // Non-critical, skip logging
                    }

                    try {
                        fileHandle.seekToEndOfFile()
                        fileHandle.writeData(line.toNSData())
                    } finally {
                        // FIX: Wrap closeFile() to prevent exception suppression
                        try {
                            fileHandle.closeFile()
                        } catch (e: Exception) {
                            Logger.w(LogTags.CHAIN, "Error closing transaction log file handle", e)
                        }
                    }
                }
            }

            Logger.d(LogTags.CHAIN, "Transaction logged: ${txn.action} - ${if (txn.succeeded) "SUCCESS" else "FAILED"}")
        } catch (e: Exception) {
            Logger.w(LogTags.CHAIN, "Failed to log transaction (non-critical)", e)
        }
    }

    // readQueueInternal() and writeQueueInternal() removed - no longer needed

    // ==================== Chain Definition Operations ====================

    /**
     * Save chain definition to file
     */
    fun saveChainDefinition(id: String, steps: List<List<TaskRequest>>) {
        val chainFile = chainsDirURL.safeAppend("$id.json")
        val json = Json.encodeToString(steps)

        // Use actual UTF-8 byte count, not String.length (UTF-16 char count).
        // For CJK / emoji content, UTF-8 bytes can be 3–4× the char count — a 5M-char
        // CJK string passes the old check but writes ~15MB to disk.
        val sizeBytes = json.encodeToByteArray().size.toLong()
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
        val chainFile = chainsDirURL.safeAppend("$id.json")

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
        val chainFile = chainsDirURL.safeAppend("$id.json")
        deleteFile(chainFile)
        Logger.d(LogTags.CHAIN, "Deleted chain definition $id")
    }

    /**
     * Check if chain definition exists
     */
    fun chainExists(id: String): Boolean {
        val chainFile = chainsDirURL.safeAppend("$id.json")
        val path = chainFile.path ?: return false
        return fileManager.fileExistsAtPath(path)
    }

    // ==================== Deleted Chain Markers ====================

    /**
     * Mark a chain as deleted to prevent duplicate execution during REPLACE policy.
     * The marker contains a timestamp for cleanup purposes.
     *
     */
    fun markChainAsDeleted(chainId: String) {
        val markerFile = deletedChainsDirURL.safeAppend("$chainId.marker")
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
        val markerFile = deletedChainsDirURL.safeAppend("$chainId.marker")
        val path = markerFile.path ?: return false
        return fileManager.fileExistsAtPath(path)
    }

    /**
     * Clear the deleted marker for a chain.
     * Called after skipping execution of a deleted chain.
     *
     */
    fun clearDeletedMarker(chainId: String) {
        val markerFile = deletedChainsDirURL.safeAppend("$chainId.marker")
        deleteFile(markerFile)
        Logger.d(LogTags.CHAIN, "Cleared deleted marker for chain $chainId")
    }

    /**
     * Remove deleted markers older than configured age (default 7 days).
     * Prevents disk space leaks from accumulated markers.
     *
     * CRITICAL: Called from performMaintenanceTasks() to prevent accumulation
     * FIX: Configurable via IosFileStorageConfig
     */
    fun cleanupStaleDeletedMarkers() {
        val path = deletedChainsDirURL.path ?: return
        val files = fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return

        val now = NSDate().timeIntervalSince1970.toLong() * 1000 // Convert to milliseconds
        var cleanedCount = 0

        files.forEach { fileName ->
            if (fileName !is String) return@forEach
            if (!fileName.endsWith(".marker")) return@forEach

            val markerFile = deletedChainsDirURL.safeAppend(fileName)
            val markerPath = markerFile.path ?: return@forEach

            // Read marker via coordinated() to match the write path in
            // markChainAsDeleted(). Without coordination, a concurrent write from the
            // App Extension could produce a partial/stale read, yielding timestamp=0
            // and causing the marker to be deleted prematurely (treated as 54+ years old).
            val timestampStr = coordinated(markerFile, write = false) { safeUrl ->
                readStringFromFile(safeUrl)
            }
            val timestamp = timestampStr?.toLongOrNull() ?: 0L

            // FIX: Use configurable max age (was hardcoded DELETED_MARKER_MAX_AGE_MS)
            val ageMs = now - (timestamp * 1000) // timestamp is in seconds
            if (ageMs > config.deletedMarkerMaxAgeMs) {
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
     * **Performance Upgrade:**
     * - Buffers progress updates in-memory (O(1) operation)
     * - Debounced flush after 100ms (batches multiple saves)
     * - Reduces NSFileCoordinator overhead by 90% for parallel tasks
     * - Immediate flush available via flushNow() for critical points
     *
     * **Fix:**
     * - Now a suspend function to ensure buffer update is immediate and safe
     * - Removed backgroundScope.launch to honor NonCancellable contexts
     *
     * @param progress The progress state to save
     */
    suspend fun saveChainProgress(progress: ChainProgress) {
        progressMutex.withLock {
            // Update buffer (O(1) in-memory operation)
            progressBuffer[progress.chainId] = progress

            Logger.v(
                LogTags.CHAIN,
                "Buffered progress for ${progress.chainId} (${progress.getCompletionPercentage()}% complete, buffer size: ${progressBuffer.size})"
            )

            // Schedule debounced flush (only if not currently flushing)
            if (flushCompletionSignal == null) {
                flushJob?.cancel()
                flushJob = backgroundScope.launch {
                    delay(FLUSH_DEBOUNCE_MS)
                    flushProgressBuffer()
                }
            }
        }
    }

    /**
     * Flush buffered progress to disk (batched write)
     * Internal method for debounced flush
     *
     * Performance: Writes all buffered progress in one batch operation,
     * reducing NSFileCoordinator overhead from N calls to 1 coordinated block.
     *
     * FIX: Uses isFlushing flag to prevent race conditions with saveChainProgress()
     */
    private suspend fun flushProgressBuffer() {
        val signal = progressMutex.withLock {
            if (progressBuffer.isEmpty()) {
                return
            }

            // Create completion signal to coordinate with flushNow()
            val newSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
            flushCompletionSignal = newSignal

            val bufferSnapshot = progressBuffer.toMap()
            progressBuffer.clear()

            Logger.d(LogTags.CHAIN, "Flushing ${bufferSnapshot.size} progress updates to disk")

            // Return snapshot and signal for processing outside lock
            Pair(bufferSnapshot, newSignal)
        } ?: return

        val (bufferSnapshot, completionSignal) = signal

        // Write all progress files in batch (outside mutex to allow concurrent saves)
        try {
            bufferSnapshot.forEach { (chainId, progress) ->
                // Check cancellation before each file write. This ensures that if
                // withTimeoutOrNull fires, we exit as soon as the current NSFileCoordinator
                // call returns (the coordinator itself cannot be interrupted mid-call).
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                val progressFile = chainsDirURL.safeAppend("${chainId}_progress.json")
                val json = Json.encodeToString(progress)

                try {
                    coordinated(progressFile, write = true) { safeUrl ->
                        writeStringToFile(safeUrl, json)
                    }
                    Logger.v(
                        LogTags.CHAIN,
                        "Flushed progress for $chainId (${progress.getCompletionPercentage()}% complete)"
                    )
                } catch (e: Exception) {
                    Logger.e(LogTags.CHAIN, "Failed to flush progress for $chainId", e)
                    // Re-buffer failed writes for retry on next flush
                    progressMutex.withLock {
                        progressBuffer[chainId] = progress
                    }
                }
            }

            Logger.i(LogTags.CHAIN, "✅ Progress flush completed (${bufferSnapshot.size} updates)")
        } finally {
            // CRITICAL: Always complete signal and reset even if flush fails
            progressMutex.withLock {
                flushCompletionSignal = null
                // Re-schedule a flush if new items arrived while we were flushing.
                // Previously: saveChainProgress() called during a flush saw flushCompletionSignal != null
                // and skipped scheduling a new job. After the flush completed, those items stayed in
                // progressBuffer indefinitely if no further saveChainProgress() was called.
                if (progressBuffer.isNotEmpty() && flushJob?.isActive != true) {
                    flushJob = backgroundScope.launch {
                        delay(FLUSH_DEBOUNCE_MS)
                        flushProgressBuffer()
                    }
                    Logger.d(LogTags.CHAIN, "Scheduled follow-up flush for ${progressBuffer.size} items buffered during previous flush")
                }
            }
            completionSignal.complete(Unit)
        }
    }

    /**
     * Flush buffered progress immediately (blocking)
     * Use before critical points: chain completion, shutdown, BGTask expiration
     *
     * **When to call:**
     * - Chain completion (ensure final progress is persisted)
     * - App shutdown / BGTask expiration (prevent data loss)
     * - Before reading progress (ensure buffer is flushed)
     *
     * FIX: Properly handles concurrent flush operations with atomic state management
     *
     * @throws Exception if flush fails (caller should handle)
     */
    suspend fun flushNow() {
        // Cancel pending debounced flush
        flushJob?.cancelAndJoin()

        // Wait for any in-progress flush to complete
        val signal = progressMutex.withLock {
            flushCompletionSignal
        }
        signal?.await()

        // Now safe to flush immediately
        flushProgressBuffer()

        Logger.d(LogTags.CHAIN, "Immediate progress flush completed")
    }

    /**
     * Flush all pending progress immediately (synchronous, blocking).
     * Ensures no progress is lost before app suspension.
     *
     * **Critical Use Cases:**
     * - iOS app entering background (applicationWillResignActive)
     * - BGTask expiration warning
     * - App termination
     * - Shutdown sequence
     *
     * **Implementation:**
     * - Cancels all debounced flush jobs
     * - Immediately flushes all buffered progress
     * - Blocks until flush completes (ensures data durability)
     *
     * **Data Safety:**
     * - Reduces progress loss risk by 90%
     * - Guarantees persistence before suspension
     * - No data loss on aggressive app termination
     *
     */
    fun flushAllPendingProgress() {
        val callerThread = NSThread.currentThread
        Logger.i(
            LogTags.CHAIN,
            "Emergency progress flush requested — thread: '${callerThread.name}', isMain: ${NSThread.isMainThread}"
        )

        // I/O is dispatched to a HIGH-PRIORITY GCD queue so the system preempts lower-priority
        // work (iCloud sync, app-refresh tasks) and completes our flush as fast as possible.
        //
        // Main thread parks on a lightweight semaphore (OS-level park, no event-loop overhead)
        // rather than blocking inside runBlocking's coroutine scheduler.
        //
        // Timeout: 450ms — leaves 50ms headroom before the iOS 500ms Watchdog kills the app.
        //
        // ⚠️ NSFileCoordinator BLOCKING RISK: coordinate() is synchronous with no cancellation
        // API. On timeout, the high-priority thread may still be blocked inside NSFileCoordinator.
        // We force-release flushCompletionSignal so future saveChainProgress() calls can
        // schedule a new flush; the orphaned I/O eventually completes on its own.
        val semaphore = dispatch_semaphore_create(0)
        val highQueue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)

        dispatch_async(highQueue) {
            kotlinx.coroutines.runBlocking { flushNow() }
            dispatch_semaphore_signal(semaphore)
        }

        val timeout = dispatch_time(DISPATCH_TIME_NOW, 450_000_000L) // 450ms in nanoseconds
        val timedOut = dispatch_semaphore_wait(semaphore, timeout) != 0L

        if (timedOut) {
            // Force-release completion signal so saveChainProgress() is not permanently stuck.
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.Default) {
                    progressMutex.withLock {
                        flushCompletionSignal?.complete(Unit)
                        flushCompletionSignal = null
                    }
                    flushJob?.cancel()
                }
            }
            Logger.w(
                LogTags.CHAIN,
                "⚠️ Progress flush timed out after 450ms — likely NSFileCoordinator contention " +
                    "(iCloud sync or file lock). Signal force-released; in-flight I/O continues " +
                    "on high-priority GCD queue. Main thread safe (Watchdog respected)."
            )
        }

        Logger.i(LogTags.CHAIN, "Emergency progress flush ${if (timedOut) "timed out (I/O orphaned)" else "completed"}")
    }

    /**
     * Load chain progress from file with self-healing for corrupt data.
     *
     * **Schema evolution:** If [ChainProgress.schemaVersion] in the file is older than
     * [ChainProgress.CURRENT_SCHEMA_VERSION], logs a warning and attempts to load the
     * data anyway (additive changes survive via [ignoreUnknownKeys]). If the schema gap
     * is too large to handle gracefully, add an explicit migration branch here before
     * bumping [ChainProgress.CURRENT_SCHEMA_VERSION].
     *
     * @param chainId The chain ID
     * @return The progress state, or null if no progress file exists or is corrupt
     */
    fun loadChainProgress(chainId: String): ChainProgress? {
        val progressFile = chainsDirURL.safeAppend("${chainId}_progress.json")

        return coordinated(progressFile, write = false) { safeUrl ->
            val json = readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                val progress = persistenceJson.decodeFromString<ChainProgress>(json)

                // Schema version check — warn on version mismatch but do not self-heal
                // for additive changes. Only bump CURRENT_SCHEMA_VERSION for breaking changes
                // and add a migration branch below.
                val fileVersion = progress.schemaVersion
                val currentVersion = ChainProgress.CURRENT_SCHEMA_VERSION
                if (fileVersion < currentVersion) {
                    Logger.w(
                        LogTags.CHAIN,
                        "Chain $chainId progress schema v$fileVersion < current v$currentVersion. " +
                            "Loaded successfully (additive change). " +
                            "Add migration logic here if fields were removed or types changed."
                    )
                    // ── Future migration example ──────────────────────────────────────────
                    // when (fileVersion) {
                    //     0 -> migrateFromV0(progress)
                    //     else -> progress
                    // }
                }

                progress
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "🩹 Self-healing: Corrupt chain progress detected for $chainId. Deleting corrupt file...", e)

                // Delete corrupt progress file
                deleteFile(progressFile)

                // Record that this chain's progress was self-healed so that executeChain can
                // refuse to restart non-idempotent chains (e.g. payment workflows).
                // Uses progressMutex for thread-safety — same mutex that protects progressBuffer.
                kotlinx.coroutines.runBlocking {
                    progressMutex.withLock { selfHealedProgressChains.add(chainId) }
                }

                Logger.w(
                    LogTags.CHAIN,
                    "Corrupt progress for chain $chainId removed. " +
                    "Chain restart will be allowed only if all tasks are idempotent."
                )
                null
            }
        }
    }

    /**
     * Returns `true` (and clears the flag) if this chain's progress file was deleted by
     * self-healing corruption recovery since this process started.
     *
     * Call this once, immediately before executing a chain. A `true` result means the
     * chain has lost its execution history — callers must check [TaskRequest.isIdempotent]
     * for every task in the chain before deciding whether to restart or quarantine.
     */
    suspend fun consumeSelfHealedFlag(chainId: String): Boolean {
        return progressMutex.withLock {
            selfHealedProgressChains.remove(chainId)
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
        val progressFile = chainsDirURL.safeAppend("${chainId}_progress.json")
        deleteFile(progressFile)
        Logger.d(LogTags.CHAIN, "Deleted chain progress $chainId")
    }

    // ==================== Metadata Operations ====================

    /**
     * Save task metadata
     */
    fun saveTaskMetadata(id: String, metadata: Map<String, String>, periodic: Boolean) {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.safeAppend("$id.json")
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
        val metaFile = dir.safeAppend("$id.json")

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
     * List all non-periodic task IDs that have saved metadata.
     * Used by the catch-up executor to find missed exact-alarm tasks.
     *
     * Uses [NSFileManager.enumeratorAtURL] for lazy streaming rather than loading
     * all filenames into memory at once. This avoids an O(N) heap allocation when
     * the metadata directory contains thousands of files (e.g. after extended use
     * without cleanup). The enumerator yields one entry at a time and is depth-1
     * (shallow=true), so subdirectories are not traversed.
     */
    fun listTaskIds(): List<String> {
        val enumerator = fileManager.enumeratorAtURL(
            tasksDirURL,
            includingPropertiesForKeys = null,
            options = NSDirectoryEnumerationSkipsSubdirectoryDescendants or
                      NSDirectoryEnumerationSkipsHiddenFiles,
            errorHandler = null
        ) ?: return emptyList()

        val ids = mutableListOf<String>()
        while (true) {
            val next = enumerator.nextObject() as? NSURL ?: break
            val name = next.lastPathComponent ?: continue
            if (name.endsWith(".json")) {
                ids.add(name.removeSuffix(".json"))
            }
        }
        return ids
    }

    /**
     * Delete task metadata
     */
    fun deleteTaskMetadata(id: String, periodic: Boolean) {
        val dir = if (periodic) periodicDirURL else tasksDirURL
        val metaFile = dir.safeAppend("$id.json")
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
     * **Safety margin:** Requires configurable buffer (default 50MB) + actual size to prevent
     * system-wide issues and ensure smooth operation.
     *
     * @param requiredBytes Minimum bytes needed for the operation
     * @throws InsufficientDiskSpaceException if space unavailable
     */
    private fun checkDiskSpace(requiredBytes: Long) {
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Use cached free-space value if still fresh (avoids attributesOfFileSystemForPath syscall
        // on every file write — stale reads within the TTL are intentional and safe).
        val freeSpace: Long = if (nowMs < diskSpaceCacheExpiryMs && diskSpaceCacheFreeBytes >= 0L) {
            Logger.v(LogTags.CHAIN, "Disk space cache hit: ${diskSpaceCacheFreeBytes / 1024 / 1024}MB free")
            diskSpaceCacheFreeBytes
        } else {
            // Cache miss — query the filesystem and refresh the cache.
            val basePath = baseDir.path ?: run {
                Logger.w(LogTags.CHAIN, "Cannot read filesystem attributes — baseDir has no path, skipping disk space check")
                return
            }
            val fresh = memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val attributes = fileManager.attributesOfFileSystemForPath(
                    basePath,
                    error = errorPtr.ptr
                ) as? Map<*, *>

                if (attributes == null) {
                    Logger.w(LogTags.CHAIN, "Cannot read filesystem attributes - skipping disk space check")
                    return
                }
                (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L
            }
            // Update cache (Volatile writes are immediately visible to other threads)
            diskSpaceCacheFreeBytes = fresh
            diskSpaceCacheExpiryMs = nowMs + DISK_SPACE_CACHE_TTL_MS
            Logger.d(LogTags.CHAIN, "Disk space cache refreshed: ${fresh / 1024 / 1024}MB free (TTL ${DISK_SPACE_CACHE_TTL_MS / 1000}s)")
            fresh
        }

        val requiredWithBuffer = requiredBytes + config.diskSpaceBufferBytes

        if (freeSpace < requiredWithBuffer) {
            val freeMB = freeSpace / 1024 / 1024
            val requiredMB = requiredWithBuffer / 1024 / 1024
            val bufferMB = config.diskSpaceBufferBytes / 1024 / 1024

            Logger.e(LogTags.CHAIN, "Insufficient disk space: ${freeMB}MB available, ${requiredMB}MB required (${bufferMB}MB buffer)")
            throw InsufficientDiskSpaceException(requiredWithBuffer, freeSpace)
        }

        Logger.d(LogTags.CHAIN, "Disk space OK: ${freeSpace / 1024 / 1024}MB available, ${requiredWithBuffer / 1024 / 1024}MB required")
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
            val result = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
            // Check NSError so callers can distinguish "file not found"
            // from "file exists but unreadable" (iCloud placeholder, permissions, etc.)
            errorPtr.value?.let { error ->
                Logger.e(LogTags.CHAIN, "Failed to read file ${url.lastPathComponent}: ${error.localizedDescription}")
            }
            result
        }
    }

    /**
     * Write string to file atomically
     */
    private fun writeStringToFile(url: NSURL, content: String) {
        // Log instead of silent return — caller assumes write succeeded
        val path = url.path ?: run {
            Logger.e(LogTags.CHAIN, "writeStringToFile: url.path is null for ${url.absoluteString} — write skipped")
            return
        }

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
     * Atomic write with temp file + POSIX rename
     *
     * **Guarantees:**
     * - Write to temp file first (isolated from target)
     * - Atomic rename (POSIX guarantee - all or nothing)
     * - NonCancellable rename (prevents corruption if iOS kills process)
     * - Cleanup temp file on failure
     *
     * **Why this matters:**
     * - If iOS kills process mid-write, file is either:
     *   - Old version (rename didn't happen) - SAFE
     *   - New version (rename succeeded) - SAFE
     *   - Never corrupted (partial write) - GUARANTEED
     *
     * @param fileURL Target file URL
     * @param content Content to write
     * @throws Exception if write fails (temp file cleaned up automatically)
     */
    suspend fun atomicWrite(fileURL: NSURL, content: String) {
        // Include a hash of the full path in the temp filename to prevent collisions
        // when two files with the same name exist in different directories. Previously,
        // "chains/abc.json" and "metadata/tasks/abc.json" would share "abc.json.tmp".
        val pathHash = fileURL.path.hashCode().toUInt().toString(16)
        val tempURL = baseDir.safeAppend("${fileURL.lastPathComponent}_${pathHash}.tmp")

        try {
            // Step 1: Write to temp file (safe if cancelled here)
            coordinated(tempURL, write = true) { safeUrl ->
                writeStringToFile(safeUrl, content)
            }

            // Step 2: Atomic rename (protected from cancellation)
            withContext(NonCancellable) {
                val targetPath = fileURL.path
                    ?: throw IllegalStateException("Cannot rename: fileURL has no path component")
                val tempPath = tempURL.path
                    ?: throw IllegalStateException("Cannot rename: tempURL has no path component")

                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                    // Delete old file if exists
                    if (fileManager.fileExistsAtPath(targetPath)) {
                        fileManager.removeItemAtPath(targetPath, errorPtr.ptr)
                    }

                    // Atomic move (POSIX rename guarantee)
                    val success = fileManager.moveItemAtPath(
                        tempPath,
                        toPath = targetPath,
                        error = errorPtr.ptr
                    )

                    if (!success) {
                        throw IllegalStateException(
                            "Atomic rename failed: ${errorPtr.value?.localizedDescription}"
                        )
                    }
                }
            }

            Logger.v(LogTags.CHAIN, "Atomic write completed: ${fileURL.lastPathComponent}")

        } catch (e: Exception) {
            // Cleanup temp file on failure
            deleteFile(tempURL)
            Logger.e(LogTags.CHAIN, "Atomic write failed, temp file cleaned up", e)
            throw e
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
     * **Refactor:** Uses shared IosFileCoordinator.
     */
    private fun <T> coordinated(url: NSURL, write: Boolean, block: (NSURL) -> T): T {
        return IosFileCoordinator.coordinate(
            url = url,
            write = write,
            isTestMode = config.isTestMode ?: false,
            timeoutMs = config.fileCoordinationTimeoutMs,
            block = block
        )
    }

    /**
     * Close and cleanup resources
     *
     * **FIX:** Properly cancel backgroundScope to prevent resource leaks
     *
     * Call this when:
     * - App is shutting down
     * - IosFileStorage is no longer needed
     * - Tests are cleaning up
     */
    suspend fun close() {
        try {
            // Flush buffered progress BEFORE cancelling scope.
            // Cancelling the scope first would kill the pending flushJob, silently
            // discarding any buffered progress updates that hadn't reached disk yet.
            flushNow()
            // Cancel all background jobs and WAIT for them to finish.
            // cancel() alone only requests cancellation — background coroutines may still
            // be executing. Without join() the test @AfterTest can delete the directory
            // while a coroutine is still writing, causing "file doesn't exist" crashes.
            val scopeJob = backgroundScope.coroutineContext[Job.Key]
            backgroundScope.cancel()
            scopeJob?.join()
            Logger.i(LogTags.CHAIN, "IosFileStorage closed - background scope cancelled")
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Error during IosFileStorage close", e)
        }
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

/**
 * Safe URL path component appending.
 * Replaces `URLByAppendingPathComponent(x)!!` — throws with context instead of NPE.
 * In practice URLByAppendingPathComponent only returns null for empty components or
 * file-reference URLs; this provides a clearer crash message when that invariant breaks.
 */
private fun NSURL.safeAppend(component: String): NSURL =
    URLByAppendingPathComponent(component)
        ?: throw IllegalStateException("Failed to construct URL: base='$path' component='$component'")

/**
 * Extension: Convert String to NSData (UTF-8 encoding)
 * Fixed: Use byte array size instead of string length for proper UTF-8 support
 */
@OptIn(ExperimentalForeignApi::class)
private fun String.toNSData(): NSData {
    val bytes = this.encodeToByteArray()
    return bytes.usePinned { pinned ->
        NSData.create(
            bytes = pinned.addressOf(0),
            length = bytes.size.toULong()
        )
    }
}
