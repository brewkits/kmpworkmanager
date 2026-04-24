package dev.brewkits.kmpworkmanager.background.data

import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import platform.Foundation.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.crc32

/**
 *
 * **Performance**: O(1) for enqueue and dequeue operations
 * - Previous implementation: O(N) - read entire file, modify, write entire file
 * - New implementation: O(1) - append single line or update head pointer
 *
 * **Architecture**:
 * ```
 * queue/
 * ├── queue.jsonl          # Append-only log (never rewritten)
 * ├── head_pointer.txt     # Current read position (simple integer)
 * └── queue_compacted.jsonl  # Generated during compaction (temporary)
 * ```
 *
 * **Features**:
 * - Thread-safe via Mutex
 * - Crash-safe via atomic file operations
 * - Automatic compaction when 80%+ items are processed
 * - Line position cache for fast random access
 * - Auto-migration from old queue format
 *
 * **Usage**:
 * ```kotlin
 * val queue = AppendOnlyQueue(baseDir)
 * queue.enqueue("item-1")  // O(1)
 * val item = queue.dequeue()  // O(1)
 * ```
 *
 * @param baseDirectoryURL Base directory URL for queue storage
 * @param compactionScope CoroutineScope for background compaction operations
 *                        Defaults to a supervised scope with Default dispatcher
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal class AppendOnlyQueue(
    private val baseDirectoryURL: NSURL,
    private val compactionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val isTestMode: Boolean = false,
    private val minFreeDiskSpaceBytes: Long = 50_000_000L
) {
    private val queueMutex = Mutex()
    private val fileManager = NSFileManager.defaultManager

    // Corruption handling
    // Both fields are read outside any lock in dequeue() ("fast check without lock").
    // Without @Volatile, the CPU may cache stale values — making the fast check unreliable in
    // Kotlin/Native's new memory model where threads share state without automatic barriers.
    @kotlin.concurrent.Volatile
    private var isQueueCorrupt = false
    @kotlin.concurrent.Volatile
    private var corruptionOffset: ULong = 0UL  // Byte offset of the first corrupt record
    private val corruptionMutex = Mutex()

    private var fileFormat: UInt? = null
    private val formatMutex = Mutex()

    // File paths
    private val queueFileURL = baseDirectoryURL.safeAppend("queue.jsonl")
    private val headPointerURL = baseDirectoryURL.safeAppend("head_pointer.txt")
    private val compactedQueueURL = baseDirectoryURL.safeAppend("queue_compacted.jsonl")
    private val indexFileURL = baseDirectoryURL.safeAppend("queue.index")

    // Line position cache: maps line index → file byte offset
    // Example: {0: 0, 1: 45, 2: 92, ...} means line 0 starts at byte 0, line 1 at byte 45, etc.
    private val linePositionCache = mutableMapOf<Int, ULong>()
    private var cacheValid = false

    // Persistent index for O(1) startup
    private val queueIndex = QueueIndex(indexFileURL)

    // Compaction threshold: trigger when 80%+ items are processed
    private val COMPACTION_THRESHOLD = 0.8

    // Maximum queue size to prevent unbounded growth
    private val MAX_QUEUE_SIZE = 10_000

    // Compaction state tracking
    private val compactionMutex = Mutex()
    private var isCompacting = false

    companion object {
        private const val QUEUE_FILENAME = "queue.jsonl"
        private const val HEAD_POINTER_FILENAME = "head_pointer.txt"

        private const val MAGIC_NUMBER: UInt = 0x4B4D5051u  // "KMPQ" in ASCII
        private const val FORMAT_VERSION: UInt = 0x00000001u  // Version 1
        private const val FORMAT_VERSION_LEGACY: UInt = 0x00000000u  // Text format
        private const val LEGACY_READ_CHUNK_SIZE: Int = 4096  // Bytes per read in legacy text-format path
    }

    init {
        // Ensure base directory exists
        ensureDirectoryExists(baseDirectoryURL)

        // Delete any orphaned compacted files from interrupted previous runs
        if (fileManager.fileExistsAtPath(compactedQueueURL.path ?: "")) {
            Logger.w(LogTags.QUEUE, "Cleaning up orphaned compaction file from previous run")
            fileManager.removeItemAtURL(compactedQueueURL, null)
        }

        detectAndMigrateIfNeeded()

        // Auto-migrate from old format if needed
        migrateQueueIfNeeded()

        // Load persisted index for O(1) startup (skip in test mode to prevent contamination)
        if (!isTestMode) {
            val savedIndex = queueIndex.loadIndex()
            if (savedIndex.isNotEmpty()) {
                linePositionCache.putAll(savedIndex)
                cacheValid = true
                Logger.i(
                    LogTags.QUEUE,
                    "✅ Queue index loaded: ${savedIndex.size} entries (O(1) startup)"
                )
            }
        }
    }

    /**
     * Enqueue an item to the queue
     * **Performance**: O(1) - appends single line to end of file
     *
     * @param item Item ID to enqueue
     * @throws IllegalStateException if queue size limit exceeded
     * @throws InsufficientDiskSpaceException if disk space unavailable
     */
    suspend fun enqueue(item: String) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            queueMutex.withLock {
                checkDiskSpace(item.encodeToByteArray().size.toLong())

                coordinated(queueFileURL, write = true) { safeUrl ->
                    // Get the size before we add to calculate the index
                    val totalLines = countTotalLines(safeUrl)
                    
                    // Append single line to queue file (O(1) operation)
                    val newOffset = appendToQueueFile(safeUrl, item)

                    // O(1) Cache Maintenance: Add the new offset instead of invalidating everything!
                    if (cacheValid || totalLines == 0) {
                        linePositionCache[totalLines] = newOffset
                        cacheValid = true
                    }

                    Logger.v(LogTags.QUEUE, "Enqueued $item at offset $newOffset")
                }
            }
        }
    }

    /**
     * Dequeue the first unprocessed item from the queue
     * **Performance**: O(1) - reads single line and updates head pointer
     *
     * @return Item ID or null if queue is empty
     */
    suspend fun dequeue(): String? = kotlinx.coroutines.withContext(Dispatchers.Default) {
        // Fast check without lock (CRITICAL: prevents race condition)
        if (isQueueCorrupt) {
            // Double mutex pattern to prevent race condition
            // CRITICAL: Must acquire in order: corruptionMutex → queueMutex
            corruptionMutex.withLock {
                queueMutex.withLock {
                    // Double-check inside lock (prevents TOCTOU issues)
                    if (isQueueCorrupt) {
                        coordinated(queueFileURL, write = true) { safeUrl ->
                            Logger.w(LogTags.QUEUE, "Queue corruption detected during dequeue. Truncating at offset $corruptionOffset...")
                            truncateAtCorruptionPoint(safeUrl)  // Safe: queueMutex already held
                            isQueueCorrupt = false
                            corruptionOffset = 0UL
                        }
                    }
                }
            }
            return@withContext null
        }

        queueMutex.withLock {
            coordinated(headPointerURL, write = true) { safeHeadUrl ->
                val headIndex = readHeadPointer(safeHeadUrl)
                // Direct read — NSFileCoordinator does not support reentrant calls, so we
                // cannot nest a second coordinated() call for queueFileURL here while already
                // inside coordinated(headPointerURL). Intra-process safety is provided by
                // queueMutex (all enqueue/dequeue/compact paths hold it). Inter-process safety
                // (App Extensions writing to the same queue) is not fully guaranteed for this
                // read; App Extensions must use the same queueMutex-based API rather than
                // writing directly to queueFileURL to avoid partial-record races.
                val item = readLineAtIndex(queueFileURL, headIndex)

                if (item != null) {
                    // Increment head pointer (O(1) operation)
                    writeHeadPointer(safeHeadUrl, headIndex + 1)

                    Logger.v(LogTags.QUEUE, "Dequeued $item. New head index: ${headIndex + 1}")

                    // Check if compaction is needed (non-blocking)
                    if (shouldCompact()) {
                        Logger.i(LogTags.QUEUE, "Compaction threshold reached. Scheduling compaction...")
                        // Launch compaction in background (non-blocking)
                        scheduleCompaction()
                    }
                } else {
                    // Distinguish legitimate "queue empty" from "file externally truncated/replaced".
                    // If the cache has an entry for this index but the read returned null, the
                    // entire file was externally truncated or replaced. Setting corruptionOffset
                    // to 0 forces truncateAtCorruptionPoint() to take the full-reset path
                    // (offset <= headerSize), discarding any corrupt content and rebuilding the
                    // file cleanly. A partial truncate would be wrong here because even bytes
                    // before our cached offset may not be valid binary records.
                    if (cacheValid && linePositionCache.containsKey(headIndex)) {
                        Logger.w(
                            LogTags.QUEUE,
                            "Expected record at index $headIndex (cached offset ${linePositionCache[headIndex]}) " +
                                "but got EOF — file appears to have been externally replaced, scheduling full reset"
                        )
                        isQueueCorrupt = true
                        corruptionOffset = 0UL  // Force full reset via truncateAtCorruptionPoint
                    } else {
                        Logger.v(LogTags.QUEUE, "Queue is empty")
                    }
                }

                item
            }
        }
    }

    /**
     * Get current queue size (number of unprocessed items)
     * **Performance**: O(1) - reads head pointer and counts lines
     */
    suspend fun getSize(): Int {
        return queueMutex.withLock {
            val headIndex = coordinated(headPointerURL, write = false) { safeHeadUrl ->
                readHeadPointer(safeHeadUrl)
            }
            val totalLines = coordinated(queueFileURL, write = false) { safeQueueUrl ->
                countTotalLines(safeQueueUrl)
            }
            maxOf(0, totalLines - headIndex)
        }
    }

    // ==================== Private Implementation ====================

    /**
     * Append a single line to the queue file
     * **Performance**: O(1) - seek to end and write
     * 
     * @return The byte offset where the new item was appended
     */
    private fun appendToQueueFile(url: NSURL, item: String): ULong {
        val path = url.path ?: throw IllegalStateException("Queue file path is null")

        // Create file if it doesn't exist
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, null, null)

            if (fileFormat == null || fileFormat == FORMAT_VERSION) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val fileHandle = NSFileHandle.fileHandleForWritingToURL(url, errorPtr.ptr)

                    if (fileHandle != null) {
                        try {
                            writeFileHeader(fileHandle)
                            fileFormat = FORMAT_VERSION
                        } finally {
                            fileHandle.closeFile()
                        }
                    }
                }
            }
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            val fileHandle = NSFileHandle.fileHandleForWritingToURL(url, errorPtr.ptr)

            if (fileHandle == null) {
                throw IllegalStateException("Failed to open queue file: ${errorPtr.value?.localizedDescription}")
            }

            try {
                // Seek to end of file (O(1))
                fileHandle.seekToEndOfFile()
                
                // Capture the starting offset for caching purposes
                val startOffset = fileHandle.offsetInFile

                when (fileFormat) {
                    FORMAT_VERSION -> {
                        // Binary format with CRC32
                        appendToQueueFileBinary(fileHandle, item)
                    }
                    else -> {
                        // Legacy text format
                        val line = "$item\n"
                        val data = line.toNSData()
                        fileHandle.writeData(data)
                    }
                }
                
                startOffset
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Read line at specified index
     * **Performance**: O(1) with cache, O(N) first time (builds cache)
     */
    private fun readLineAtIndex(url: NSURL, index: Int): String? {
        val path = url.path ?: return null

        if (!fileManager.fileExistsAtPath(path)) {
            return null
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            val fileHandle = NSFileHandle.fileHandleForReadingFromURL(url, errorPtr.ptr)
                ?: return null

            try {
                if (fileFormat == FORMAT_VERSION && index == 0) {
                    // Skip 8-byte header (magic + version)
                    fileHandle.seekToFileOffset(8u)
                }

                // Check if we have cached position
                val cachedOffset = if (cacheValid) linePositionCache[index] else null

                if (cachedOffset != null) {
                    // Fast path: Use cached offset (O(1))
                    fileHandle.seekToFileOffset(cachedOffset)

                    return when (fileFormat) {
                        FORMAT_VERSION -> readSingleRecordWithValidation(fileHandle)
                        else -> readSingleLine(fileHandle)
                    }
                } else {
                    // Slow path: Build cache by scanning (O(N) but only first time)
                    return buildCacheAndReadLine(fileHandle, index)
                }
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Build line position cache and read line at index
     * Only called once per queue lifecycle
     */
    private fun buildCacheAndReadLine(fileHandle: NSFileHandle, targetIndex: Int): String? {
        linePositionCache.clear()
        var currentIndex = 0
        var currentOffset = 0UL

        if (fileFormat == FORMAT_VERSION) {
            fileHandle.seekToFileOffset(8u) // Skip 8-byte header
            currentOffset = 8UL
        } else {
            fileHandle.seekToFileOffset(0u)
        }

        while (true) {
            val startOffset = currentOffset

            val line = when (fileFormat) {
                FORMAT_VERSION -> readSingleRecordWithValidation(fileHandle)
                else -> readSingleLine(fileHandle)
            } ?: break

            // Cache this line's position
            linePositionCache[currentIndex] = startOffset

            if (currentIndex == targetIndex) {
                cacheValid = true
                // Save index after cache rebuild (async, non-blocking)
                saveIndexAsync()
                return line
            }

            currentIndex++
            currentOffset = fileHandle.offsetInFile
        }

        cacheValid = true
        // Save index after cache rebuild (async, non-blocking)
        saveIndexAsync()
        return null  // Index out of bounds
    }

    /**
     * Save index asynchronously
     * Non-blocking - runs in background scope
     */
    private fun saveIndexAsync() {
        if (isTestMode) return // Don't save index in test mode to prevent data contamination
        
        val snapshot = HashMap(linePositionCache) // snapshot while caller holds queueMutex
        compactionScope.launch {
            try {
                queueIndex.saveIndex(snapshot)
            } catch (e: Exception) {
                Logger.w(LogTags.QUEUE, "Failed to save queue index (non-critical)", e)
            }
        }
    }

    /**
     * Read a single line from file handle at current position
     */
    private fun readSingleLine(fileHandle: NSFileHandle): String? {
        val lineStartOffset = fileHandle.offsetInFile
        return try {
            val result = StringBuilder()

            while (true) {
                val chunkStartOffset = fileHandle.offsetInFile
                val data = fileHandle.readDataOfLength(LEGACY_READ_CHUNK_SIZE.toULong())

                if (data.length == 0UL) {
                    return if (result.isEmpty()) null else result.toString()
                }

                val bytes = data.bytes?.reinterpret<ByteVar>()
                    ?: throw CorruptQueueException("Cannot read chunk bytes")

                val len = data.length.toInt()
                var newlineIndex = -1
                for (i in 0 until len) {
                    if (bytes[i].toInt().toChar() == '\n') {
                        newlineIndex = i
                        break
                    }
                }

                if (newlineIndex >= 0) {
                    // Append bytes before the newline, then seek past it
                    for (i in 0 until newlineIndex) {
                        result.append(bytes[i].toInt().toChar())
                    }
                    fileHandle.seekToFileOffset(chunkStartOffset + (newlineIndex + 1).toULong())
                    return result.toString()
                } else {
                    // No newline in this chunk — append all and continue
                    for (i in 0 until len) {
                        result.append(bytes[i].toInt().toChar())
                    }
                }
            }

            if (result.isEmpty()) null else result.toString()
        } catch (e: Exception) {
            Logger.e(LogTags.QUEUE, "Corrupt queue line detected at offset $lineStartOffset", e)
            isQueueCorrupt = true
            corruptionOffset = lineStartOffset
            return null
        }
    }

    /**
     * Read head pointer value (current read position)
     * **Performance**: O(1)
     */
    private fun readHeadPointer(url: NSURL): Int {
        val path = url.path ?: return 0

        if (!fileManager.fileExistsAtPath(path)) {
            // First time: Create head pointer at 0
            writeHeadPointer(url, 0)
            return 0
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            errorPtr.value?.let { error ->
                Logger.e(LogTags.QUEUE, "Failed to read head pointer, defaulting to 0: ${error.localizedDescription}")
            }

            content?.toString()?.trim()?.toIntOrNull() ?: 0
        }
    }

    /**
     * Write head pointer value
     * **Performance**: O(1)
     */
    private fun writeHeadPointer(url: NSURL, index: Int) {
        val path = url.path ?: throw IllegalStateException("Head pointer path is null")

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = index.toString() as NSString

            val success = content.writeToFile(
                path,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            if (!success) {
                throw IllegalStateException("Failed to write head pointer: ${errorPtr.value?.localizedDescription}")
            }
        }
    }

    // Convenience wrappers using class-level file URLs
    private fun readHeadPointer(): Int = readHeadPointer(headPointerURL)
    private fun writeHeadPointer(index: Int) = writeHeadPointer(headPointerURL, index)
    private fun countTotalLines(): Int = countTotalLines(queueFileURL)

    /**
     * Get queue size (unprocessed items)
     */
    private fun getQueueSizeInternal(): Int {
        val headIndex = readHeadPointer()
        val totalLines = countTotalLines()
        return maxOf(0, totalLines - headIndex)
    }

    /**
     * Get all unprocessed items in the queue.
     * **Performance**: O(N) - reads each unprocessed item from disk
     */
    suspend fun getAllItems(): List<String> = kotlinx.coroutines.withContext(Dispatchers.Default) {
        queueMutex.withLock {
            val headIndex = readHeadPointer()
            val totalLines = countTotalLines()
            val items = mutableListOf<String>()

            coordinated(queueFileURL, write = false) { safeUrl ->
                for (i in headIndex until totalLines) {
                    val item = readLineAtIndex(safeUrl, i)
                    if (item != null) {
                        items.add(item)
                    }
                }
            }
            items
        }
    }

    /**
     * Atomically replace the entire queue with a new list of items.
     * Used for sorting or reordering the queue without data loss.
     *
     * @param newItems The new list of items to replace the queue contents
     */
    suspend fun replaceContents(newItems: List<String>) = kotlinx.coroutines.withContext(Dispatchers.Default) {
        queueMutex.withLock {
            val basePath = baseDirectoryURL.path
            if (basePath == null || !fileManager.fileExistsAtPath(basePath)) {
                Logger.w(LogTags.QUEUE, "Base directory no longer exists - skipping replacement")
                return@withLock
            }

            coordinated(queueFileURL, write = true) { safeQueueUrl ->
                Logger.d(LogTags.QUEUE, "Replacing queue contents atomically (${newItems.size} items)...")

                // Step 1: Write to temporary file
                writeItemsToFile(compactedQueueURL, newItems)

                // Step 2: Invalidate cache before replacement
                linePositionCache.clear()
                cacheValid = false

                // Step 3: Atomically replace
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val ok = fileManager.replaceItemAtURL(
                        originalItemURL = safeQueueUrl,
                        withItemAtURL = compactedQueueURL,
                        backupItemName = null,
                        options = NSFileManagerItemReplacementWithoutDeletingBackupItem,
                        resultingItemURL = null,
                        error = errorPtr.ptr
                    )

                    if (!ok) {
                        val error = errorPtr.value
                        if (error?.code == NSFileNoSuchFileError || error?.code == NSFileReadNoSuchFileError) {
                            Logger.w(LogTags.QUEUE, "replaceItemAtURL not available, using fallback")
                            val queuePath = safeQueueUrl.path ?: throw IllegalStateException("Queue file path is null")
                            val compactedPath = compactedQueueURL.path ?: throw IllegalStateException("Compacted file path is null")
                            fileManager.removeItemAtPath(queuePath, errorPtr.ptr)
                            val success = fileManager.moveItemAtPath(compactedPath, toPath = queuePath, error = errorPtr.ptr)
                            if (!success) {
                                throw IllegalStateException("Failed to replace queue file: ${error?.localizedDescription}")
                            }
                        } else {
                            throw IllegalStateException("Failed to replace queue file atomically: ${error?.localizedDescription}")
                        }
                    }
                }

                // Step 4: Reset head pointer to 0 since we only wrote unprocessed items
                writeHeadPointer(0)
                queueIndex.deleteIndex()

                Logger.i(LogTags.QUEUE, "Queue contents replaced successfully (${newItems.size} items).")
            }
        }
    }

    /**
     * Count records in the queue file.
     *
     * For the binary format (FORMAT_VERSION), records are parsed by their
     * length field so that incidental 0x0A bytes inside the length, data, or
     * CRC32 fields are never mistaken for record terminators.
     *
     * For the legacy text format each line ends with exactly one '\n', so
     * counting newlines is correct.
     */
    private fun countTotalLines(url: NSURL): Int {
        val path = url.path ?: return 0

        if (!fileManager.fileExistsAtPath(path)) {
            return 0
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForReadingFromURL(
                url,
                error = errorPtr.ptr
            )

            if (fileHandle == null) {
                Logger.w(LogTags.QUEUE, "Failed to open file for line counting: ${errorPtr.value?.localizedDescription}")
                return 0
            }

            try {
                if (fileFormat == FORMAT_VERSION) {
                    // Binary format: navigate by length fields to avoid false newline counts
                    fileHandle.seekToFileOffset(8u) // skip 8-byte header (magic + version)
                    var count = 0
                    loop@ while (true) {
                        val lengthData = fileHandle.readDataOfLength(4u)
                        if (lengthData.length < 4UL) break@loop
                        val lengthBytes = lengthData.bytes?.reinterpret<ByteVar>() ?: break@loop
                        val length = readUIntFromBytes(lengthBytes)
                        if (length == 0u || length > 10_000_000u) break@loop
                        // Skip: data (length bytes) + CRC32 (4 bytes) + newline (1 byte)
                        fileHandle.seekToFileOffset(fileHandle.offsetInFile + length.toULong() + 5UL)
                        count++
                    }
                    return count
                } else {
                    // Legacy text format: count newline characters
                    var lineCount = 0
                    val chunkSize = 8192UL
                    while (true) {
                        val data = fileHandle.readDataOfLength(chunkSize)
                        if (data.length == 0UL) break
                        val byteArray = ByteArray(data.length.toInt())
                        byteArray.usePinned { pinned ->
                            data.getBytes(pinned.addressOf(0), data.length)
                        }
                        for (byte in byteArray) {
                            if (byte == '\n'.code.toByte()) lineCount++
                        }
                    }
                    return lineCount
                }
            } catch (e: Exception) {
                Logger.e(LogTags.QUEUE, "Error counting lines: ${e.message}")
                return 0
            } finally {
                // Guaranteed close — Kotlin executes finally even on non-local returns,
                // so a single finally replaces the previous explicit closeFile() in each
                // branch and prevents double-close if the try-internal close had thrown.
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Check if compaction is needed
     * Compaction is beneficial when 80%+ of items are processed
     */
    private fun shouldCompact(): Boolean {
        val headIndex = readHeadPointer()
        val totalLines = countTotalLines()

        if (totalLines == 0) return false

        val processedRatio = headIndex.toDouble() / totalLines
        return processedRatio >= COMPACTION_THRESHOLD && headIndex > 100  // Only compact if significant waste
    }

    /**
     * Schedule background compaction (non-blocking)
     * Compaction runs in a separate coroutine to avoid blocking dequeue operations
     *
     */
    private fun scheduleCompaction() {
        // Launch in compactionScope to perform async mutex check
        compactionScope.launch {
            // Atomic check-and-set protected by mutex
            val shouldCompact = compactionMutex.withLock {
                if (isCompacting) {
                    false // Already compacting
                } else {
                    isCompacting = true
                    true
                }
            }

            if (!shouldCompact) {
                Logger.w(LogTags.CHAIN, "Compaction already in progress. Skipping.")
                return@launch
            }

            try {
                compactQueue()
                Logger.i(LogTags.CHAIN, "Background compaction completed successfully")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Background compaction failed: ${e.message}", e)
            } finally {
                // Reset flag under mutex protection
                compactionMutex.withLock {
                    isCompacting = false
                }
            }
        }
    }

    /**
     * Compact the queue by removing processed items
     * **Algorithm**:
     * 1. Read all unprocessed items (from head to end)
     * 2. Write to temporary compacted file
     * 3. Atomically replace old file with compacted file
     * 4. Reset head pointer to 0
     * 5. Invalidate cache
     *
     * **Thread-safety**: Uses queueMutex to ensure exclusive access
     * **Crash-safety**: Uses atomic file replacement (write to temp, then move)
     */
    private suspend fun compactQueue() {
        queueMutex.withLock {
            // Safety check: Verify base directory still exists before proceeding
            val basePath = baseDirectoryURL.path
            if (basePath == null || !fileManager.fileExistsAtPath(basePath)) {
                Logger.w(LogTags.CHAIN, "Base directory no longer exists - skipping compaction")
                return@withLock
            }

            coordinated(queueFileURL, write = true) { safeQueueUrl ->
                Logger.i(LogTags.CHAIN, "Starting queue compaction...")

                // Direct read — no nested coordination (NSFileCoordinator is not reentrant).
                val headIndex = readHeadPointer()
                val totalLines = countTotalLines(safeQueueUrl)
                val unprocessedCount = totalLines - headIndex

                if (unprocessedCount <= 0) {
                    Logger.i(LogTags.CHAIN, "Queue is empty. No compaction needed.")
                    cacheValid = false
                    linePositionCache.clear()
                    // Delete index when queue is empty
                    queueIndex.deleteIndex()
                    return@coordinated
                }

                // Step 1: Read all unprocessed items
                val unprocessedItems = mutableListOf<String>()
                for (i in headIndex until totalLines) {
                    val item = readLineAtIndex(safeQueueUrl, i)
                    if (item != null) {
                        unprocessedItems.add(item)
                    }
                }

                Logger.d(LogTags.CHAIN, "Compacting: $unprocessedCount unprocessed items (${headIndex} processed)")

                // Step 2: Write to temporary compacted file
                writeItemsToFile(compactedQueueURL, unprocessedItems)

                // Step 3: Invalidate cache BEFORE replacing the file.
                // This prevents any concurrent reader (e.g. test-mode bypass) from using
                // stale cache offsets that point to positions in the OLD file after the
                // replacement swaps in the NEW file. Readers encountering an invalid cache
                // rebuild from the new file correctly.
                linePositionCache.clear()
                cacheValid = false

                // Step 3b: Atomically replace old file with compacted file
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                    // Use replaceItemAtURL for atomic replacement
                    // This ensures crash-safety: if interrupted, either old or new file exists
                    val ok = fileManager.replaceItemAtURL(
                        originalItemURL = safeQueueUrl,
                        withItemAtURL = compactedQueueURL,
                        backupItemName = null,
                        options = NSFileManagerItemReplacementWithoutDeletingBackupItem,
                        resultingItemURL = null,
                        error = errorPtr.ptr
                    )

                    if (!ok) {
                        val error = errorPtr.value
                        // Fallback to non-atomic replacement if replaceItemAtURL not supported
                        if (error?.code == NSFileNoSuchFileError || error?.code == NSFileReadNoSuchFileError) {
                            Logger.w(LogTags.QUEUE, "replaceItemAtURL not available, using fallback")
                            val queuePath = safeQueueUrl.path ?: throw IllegalStateException("Queue file path is null")
                            val compactedPath = compactedQueueURL.path ?: throw IllegalStateException("Compacted file path is null")

                            // Delete old queue file
                            fileManager.removeItemAtPath(queuePath, errorPtr.ptr)

                            // Move compacted file to queue file
                            val success = fileManager.moveItemAtPath(
                                compactedPath,
                                toPath = queuePath,
                                error = errorPtr.ptr
                            )

                            if (!success) {
                                throw IllegalStateException("Failed to replace queue file: ${error?.localizedDescription}")
                            }
                        } else {
                            throw IllegalStateException("Failed to replace queue file atomically: ${error?.localizedDescription}")
                        }
                    }
                }

                // Step 4: Reset head pointer to 0 (direct write — no nested coordination).
                writeHeadPointer(0)

                // Cache was already invalidated in Step 3 (before file replacement).
                // Delete index after compaction (will be regenerated on next access)
                queueIndex.deleteIndex()

                Logger.i(LogTags.CHAIN, "Compaction complete. Reduced from $totalLines to $unprocessedCount items.")
            }
        }
    }

    /**
     * Write items to a file (helper for compaction)
     */
    private fun writeItemsToFile(fileURL: NSURL, items: List<String>) {
        val path = fileURL.path ?: throw IllegalStateException("File path is null")

        // Create or overwrite file
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            // Delete if exists
            if (fileManager.fileExistsAtPath(path)) {
                fileManager.removeItemAtPath(path, errorPtr.ptr)
            }

            // Create new file
            fileManager.createFileAtPath(path, null, null)

            val fileHandle = NSFileHandle.fileHandleForWritingToURL(fileURL, errorPtr.ptr)

            if (fileHandle == null) {
                throw IllegalStateException("Failed to open file for writing: ${errorPtr.value?.localizedDescription}")
            }

            try {
                if (fileFormat == FORMAT_VERSION) {
                    writeFileHeader(fileHandle)
                }

                // Write all items
                items.forEach { item ->
                    when (fileFormat) {
                        FORMAT_VERSION -> {
                            // Binary format with CRC32
                            appendToQueueFileBinary(fileHandle, item)
                        }
                        else -> {
                            // Legacy text format
                            val line = "$item\n"
                            val data = line.toNSData()
                            fileHandle.writeData(data)
                        }
                    }
                }
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Detect file format and migrate to binary if needed
     */
    private fun detectAndMigrateIfNeeded() {
        val queuePath = queueFileURL.path ?: return

        // If queue file doesn't exist, create new binary format
        if (!fileManager.fileExistsAtPath(queuePath)) {
            fileFormat = FORMAT_VERSION
            Logger.d(LogTags.QUEUE, "New queue - using binary format v$FORMAT_VERSION")
            return
        }

        // Read first 4 bytes to check for magic number
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForReadingFromURL(queueFileURL, errorPtr.ptr)

            if (fileHandle == null) {
                Logger.w(LogTags.QUEUE, "Cannot open queue file for format detection")
                fileFormat = FORMAT_VERSION_LEGACY
                return
            }

            try {
                val data = fileHandle.readDataOfLength(4u)

                if (data.length < 4UL) {
                    // Empty or very small file - treat as legacy
                    fileFormat = FORMAT_VERSION_LEGACY
                    Logger.d(LogTags.QUEUE, "Empty/small queue - legacy format")
                    return
                }

                // Check for magic number
                val bytes = data.bytes?.reinterpret<ByteVar>()
                if (bytes != null) {
                    val magic = readUIntFromBytes(bytes)

                    if (magic == MAGIC_NUMBER) {
                        // Binary format - read version
                        val versionData = fileHandle.readDataOfLength(4u)
                        if (versionData.length == 4UL) {
                            val versionBytes = versionData.bytes?.reinterpret<ByteVar>()
                            fileFormat = if (versionBytes != null) readUIntFromBytes(versionBytes) else FORMAT_VERSION
                            Logger.i(LogTags.QUEUE, "Detected binary format v$fileFormat")
                        }
                    } else {
                        // No magic number - legacy text format
                        fileFormat = FORMAT_VERSION_LEGACY
                        Logger.i(LogTags.QUEUE, "Detected legacy text format - migration needed")

                        // Trigger migration. The fileHandle will be safely closed in the finally block.
                        // We must NOT call fileHandle.closeFile() here, otherwise finally block will crash.
                        migrateFromTextToBinary()
                        return
                    }
                }
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Migrate from text (JSONL) to binary format with CRC32
     *
     * Steps:
     * 1. Rename queue.jsonl → queue.jsonl.legacy
     * 2. Read legacy file (text format)
     * 3. Create new binary file with magic header
     * 4. Write each item in binary format with CRC32
     * 5. Reset head pointer
     * 6. Delete legacy file
     * 7. Verify migration succeeded
     */
    private fun migrateFromTextToBinary() {
        val queuePath = queueFileURL.path ?: throw IllegalStateException("Queue path is null")
        val legacyPath = "$queuePath.legacy"

        Logger.i(LogTags.QUEUE, "Starting text → binary migration...")

        try {
            // Read current head pointer BEFORE renaming, so we can skip
            // already-dequeued items. Without this, upgrading re-processes every item in
            // the legacy file from the start, re-running already-completed chains.
            val existingHeadIndex = readHeadPointer()
            if (existingHeadIndex > 0) {
                Logger.i(LogTags.QUEUE, "Migration: skipping first $existingHeadIndex already-processed items")
            }

            // Step 1: Rename to .legacy
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val success = fileManager.moveItemAtPath(queuePath, toPath = legacyPath, error = errorPtr.ptr)

                if (!success) {
                    throw IllegalStateException("Failed to rename queue file: ${errorPtr.value?.localizedDescription}")
                }
            }

            // Step 2: Read only unprocessed items from legacy file (skip first existingHeadIndex lines)
            val items = mutableListOf<String>()
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val content = NSString.stringWithContentsOfFile(
                    legacyPath,
                    encoding = NSUTF8StringEncoding,
                    error = errorPtr.ptr
                )

                if (content != null) {
                    content.split("\n").forEachIndexed { lineIndex, line ->
                        if (lineIndex >= existingHeadIndex) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                items.add(trimmed)
                            }
                        }
                    }
                }
            }

            Logger.i(LogTags.QUEUE, "Read ${items.size} unprocessed items from legacy queue (skipped $existingHeadIndex)")

            // Step 3: Create new binary file with header
            fileManager.createFileAtPath(queuePath, null, null)

            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)

                if (fileHandle == null) {
                    throw IllegalStateException("Failed to create binary queue file: ${errorPtr.value?.localizedDescription}")
                }

                try {
                    // Write magic number and version
                    writeFileHeader(fileHandle)

                    // Step 4: Write each item in binary format
                    items.forEach { item ->
                        appendToQueueFileBinary(fileHandle, item)
                    }

                    Logger.i(LogTags.QUEUE, "Wrote ${items.size} items in binary format")
                } finally {
                    fileHandle.closeFile()
                }
            }

            // Step 5: Reset head pointer to 0 (items list already excludes processed items)
            writeHeadPointer(0)

            // Step 6: Delete legacy file
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.removeItemAtPath(legacyPath, errorPtr.ptr)
            }

            // Step 7: Update format version
            fileFormat = FORMAT_VERSION
            cacheValid = false
            linePositionCache.clear()

            Logger.i(LogTags.QUEUE, "✅ Migration complete: ${items.size} items migrated to binary format")

        } catch (e: Exception) {
            Logger.e(LogTags.QUEUE, "Migration failed - attempting rollback", e)

            // Rollback: restore legacy file if it exists
            if (fileManager.fileExistsAtPath(legacyPath)) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                    // Delete failed binary file
                    if (fileManager.fileExistsAtPath(queuePath)) {
                        fileManager.removeItemAtPath(queuePath, errorPtr.ptr)
                    }

                    // Restore legacy file
                    fileManager.moveItemAtPath(legacyPath, toPath = queuePath, error = errorPtr.ptr)
                }

                fileFormat = FORMAT_VERSION_LEGACY
                Logger.w(LogTags.QUEUE, "Rollback complete - reverted to legacy format")
            }

            throw e
        }
    }

    /**
     * Write binary file header (magic number + version)
     */
    private fun writeFileHeader(fileHandle: NSFileHandle) {
        // Write magic number (4 bytes)
        fileHandle.writeData(MAGIC_NUMBER.toByteArray().toNSData())

        // Write format version (4 bytes)
        fileHandle.writeData(FORMAT_VERSION.toByteArray().toNSData())
    }

    /**
     * Detect and migrate old queue format if needed
     * Old format: Single queue.jsonl file, no head pointer
     * New format: queue.jsonl + head_pointer.txt
     */
    private fun migrateQueueIfNeeded() {
        val queuePath = queueFileURL.path ?: return
        val headPointerPath = headPointerURL.path ?: return

        // Check if old format exists (queue file exists but no head pointer)
        if (fileManager.fileExistsAtPath(queuePath) && !fileManager.fileExistsAtPath(headPointerPath)) {
            Logger.i(LogTags.CHAIN, "Detecting old queue format. Migrating to append-only format...")

            // Old format already uses JSONL (one line per item)
            // Just create head pointer at 0
            writeHeadPointer(0)

            Logger.i(LogTags.CHAIN, "Migration complete. Queue upgraded to append-only format with head pointer.")
        }
    }

    /**
     * Ensure directory exists
     */
    private fun ensureDirectoryExists(dirURL: NSURL) {
        val path = dirURL.path ?: return

        if (!fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                // NSFileProtectionCompleteUntilFirstUserAuthentication: accessible to background
                // tasks after first unlock. Default (NSFileProtectionComplete) blocks BGTask access.
                val attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
                fileManager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = attributes,
                    error = errorPtr.ptr
                )
            }
        }
    }

    /**
     * Execute block with file coordination
     *
     * **Refactor:** Uses shared IosFileCoordinator for inter-process safety.
     */
    private suspend fun <T> coordinated(url: NSURL, write: Boolean, block: (NSURL) -> T): T {
        return IosFileCoordinator.coordinate(
            url = url,
            write = write,
            isTestMode = false, // Or auto-detect
            block = block
        )
    }


    /**
     * Check if sufficient disk space is available
     *
     * @param requiredBytes Minimum bytes needed for the operation
     * @throws InsufficientDiskSpaceException if space unavailable
     */
    private fun checkDiskSpace(requiredBytes: Long) {
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            // Get filesystem attributes for base directory
            val basePath = baseDirectoryURL.path ?: return
            val attributes = fileManager.attributesOfFileSystemForPath(
                basePath,
                error = errorPtr.ptr
            ) as? Map<*, *>

            if (attributes == null) {
                // Cannot read attributes - skip check rather than fail
                Logger.w(LogTags.QUEUE, "Cannot read filesystem attributes - skipping disk space check")
                return
            }

            // Get free space
            val freeSpace = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L

            // Require configured buffer + actual size
            val requiredWithBuffer = requiredBytes + minFreeDiskSpaceBytes

            if (freeSpace < requiredWithBuffer) {
                val freeMB = freeSpace / 1024 / 1024
                val requiredMB = requiredWithBuffer / 1024 / 1024

                Logger.e(LogTags.QUEUE, "Insufficient disk space: ${freeMB}MB available, ${requiredMB}MB required")
                throw InsufficientDiskSpaceException(requiredWithBuffer, freeSpace)
            }
        }
    }

    /**
     * Truncate the queue file at the first corrupt record, preserving all valid
     * records that precede it.  Falls back to full reset only when corruption is
     * at or before the file header (nothing salvageable).
     *
     * CRITICAL: Assumes queueMutex already held by caller.
     */
    private fun truncateAtCorruptionPoint(url: NSURL) {
        val headerSize = if (fileFormat == FORMAT_VERSION) 8UL else 0UL

        if (corruptionOffset <= headerSize) {
            Logger.w(LogTags.QUEUE, "Corruption at offset $corruptionOffset (header region). Performing full reset.")
            resetQueueInternal()
            return
        }

        Logger.w(
            LogTags.QUEUE,
            "Truncating queue at corruption offset $corruptionOffset, " +
                    "preserving ${corruptionOffset - headerSize} bytes of valid data"
        )

        val path = url.path
        if (path == null) {
            resetQueueInternal()
            return
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForWritingToURL(url, errorPtr.ptr)
            if (fileHandle != null) {
                try {
                    fileHandle.truncateFileAtOffset(corruptionOffset)
                } finally {
                    fileHandle.closeFile()
                }
            } else {
                Logger.e(LogTags.QUEUE, "Cannot open queue for truncation. Performing full reset.")
                resetQueueInternal()
                return
            }
        }

        // Invalidate cache — record boundaries after the truncation point are gone
        linePositionCache.clear()
        cacheValid = false

        Logger.i(LogTags.QUEUE, "Queue truncated successfully. Valid records preserved up to offset $corruptionOffset.")
    }

    /**
     * Reset queue due to corruption (internal version - assumes queueMutex already held)
     *
     * CRITICAL: This method assumes the caller already holds queueMutex
     * It will NOT acquire the mutex itself to prevent deadlock
     */
    private fun resetQueueInternal() {
        Logger.w(LogTags.QUEUE, "Resetting corrupted queue...")

        try {
            val queuePath = queueFileURL.path
            val headPath = headPointerURL.path

            // Delete queue files safely
            if (queuePath != null && fileManager.fileExistsAtPath(queuePath)) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    fileManager.removeItemAtPath(queuePath, errorPtr.ptr)
                }
            }

            if (headPath != null && fileManager.fileExistsAtPath(headPath)) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    fileManager.removeItemAtPath(headPath, errorPtr.ptr)
                }
            }

            // Recreate empty files with binary format
            if (queuePath != null) {
                fileManager.createFileAtPath(queuePath, null, null)

                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)

                    if (fileHandle != null) {
                        try {
                            writeFileHeader(fileHandle)
                        } finally {
                            fileHandle.closeFile()
                        }
                    }
                }
            }

            writeHeadPointer(0)

            // Clear cache, reset format, and clear corruption state
            linePositionCache.clear()
            cacheValid = false
            isQueueCorrupt = false
            corruptionOffset = 0UL

            Logger.i(LogTags.QUEUE, "Queue reset complete. All data cleared (binary format).")
        } catch (e: Exception) {
            Logger.e(LogTags.QUEUE, "Failed to reset queue", e)
            throw e
        }
    }

    /**
     * Public API to reset corrupted queue
     *
     * This is the public-facing reset method that handles mutex acquisition
     */
    suspend fun resetQueue() {
        queueMutex.withLock {
            coordinated(queueFileURL, write = true) {
                resetQueueInternal()
            }
        }
    }

    /**
     * Shutdown the queue and cancel all background operations
     * Call this before disposing the queue (e.g., in tests)
     */
    fun shutdown() {
        try {
            // Cancel all background compaction operations
            compactionScope.cancel()
            Logger.d(LogTags.QUEUE, "Queue shutdown - background operations cancelled")
        } catch (e: Exception) {
            Logger.w(LogTags.QUEUE, "Error during queue shutdown", e)
        }
    }

    /**
     * String to NSData conversion helper
     */
    private fun String.toNSData(): NSData {
        val bytes = this.encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }

    // ==================== Binary Format Helpers ====================

    /**
     * Append item to queue file in binary format with CRC32
     * Format: [length:4][data:length][crc32:4][\n:1]
     *
     * Combined into a single write to reduce memory pinning overhead.
     */
    private fun appendToQueueFileBinary(fileHandle: NSFileHandle, item: String) {
        val jsonBytes = item.encodeToByteArray()
        val length = jsonBytes.size.toUInt()
        val crc = jsonBytes.crc32()
        val newline = "\n".encodeToByteArray()

        // Total size = 4 (length) + json.size + 4 (crc) + 1 (\n)
        val totalSize = 4 + jsonBytes.size + 4 + 1
        val combined = ByteArray(totalSize)
        
        // Manual copy is faster than multiple toNSData calls
        val lengthBytes = length.toByteArray()
        val crcBytes = crc.toByteArray()
        
        lengthBytes.copyInto(combined, 0)
        jsonBytes.copyInto(combined, 4)
        crcBytes.copyInto(combined, 4 + jsonBytes.size)
        newline.copyInto(combined, 4 + jsonBytes.size + 4)

        fileHandle.writeData(combined.toNSData())
    }

    /**
     * Read single record from binary format with CRC32 validation
     * Format: [length:4][data:length][crc32:4][\n:1]
     *
     * Reads length first, then the entire remaining record (data + crc + \n) in one syscall.
     *
     * @return JSON string or null if EOF/corrupt
     */
    private fun readSingleRecordWithValidation(fileHandle: NSFileHandle): String? {
        val recordStartOffset = fileHandle.offsetInFile
        return try {
            // Syscall 1: Read length (4 bytes)
            val lengthData = fileHandle.readDataOfLength(4u)
            if (lengthData.length < 4uL) return null // EOF

            val lengthBytes = lengthData.bytes?.reinterpret<ByteVar>()
                ?: throw CorruptQueueException("Cannot read length bytes")
            val length = readUIntFromBytes(lengthBytes)

            if (length > 10_000_000u) { // Sanity check: max 10MB per record
                throw CorruptQueueException("Invalid record length: $length")
            }

            // Syscall 2: Read data + CRC + Newline in ONE GO
            // total remaining = length + 4 (crc) + 1 (newline)
            val totalRemaining = length.toULong() + 4uL + 1uL
            val restData = fileHandle.readDataOfLength(totalRemaining)
            if (restData.length < totalRemaining) {
                throw CorruptQueueException("Incomplete record read: expected $totalRemaining, got ${restData.length}")
            }

            val restPtr = restData.bytes?.reinterpret<ByteVar>()
                ?: throw CorruptQueueException("Cannot access rest data bytes")

            // Copy JSON data into ByteArray
            val jsonBytes = ByteArray(length.toInt()) { i -> restPtr[i].toByte() }

            // Extract CRC (4 bytes starting after JSON)
            val expectedCrc = readUIntFromBytes(restPtr.plus(length.toInt())!!)

            // Validate CRC
            val actualCrc = jsonBytes.crc32()
            if (expectedCrc != actualCrc) {
                Logger.e(LogTags.QUEUE, "CRC mismatch! Expected: ${expectedCrc.toString(16)}, Actual: ${actualCrc.toString(16)}")
                throw CorruptQueueException("CRC32 validation failed")
            }

            jsonBytes.decodeToString()

        } catch (e: CorruptQueueException) {
            Logger.e(LogTags.QUEUE, "Corrupt binary record detected at offset $recordStartOffset", e)
            isQueueCorrupt = true
            corruptionOffset = recordStartOffset
            return null
        } catch (e: Exception) {
            Logger.e(LogTags.QUEUE, "Error reading binary record at offset $recordStartOffset", e)
            isQueueCorrupt = true
            corruptionOffset = recordStartOffset
            return null
        }
    }

    /**
     * Convert UInt to ByteArray (Little Endian)
     */
    private fun UInt.toByteArray(): ByteArray {
        return byteArrayOf(
            (this and 0xFFu).toByte(),
            ((this shr 8) and 0xFFu).toByte(),
            ((this shr 16) and 0xFFu).toByte(),
            ((this shr 24) and 0xFFu).toByte()
        )
    }

    /**
     * Convert ByteArray to NSData
     */
    private fun ByteArray.toNSData(): NSData {
        if (this.isEmpty()) return NSData()
        return this.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }

    /**
     * Read UInt from bytes (Little Endian)
     */
    private fun readUIntFromBytes(bytes: CPointer<ByteVar>): UInt {
        val b0 = bytes[0].toUByte().toUInt()
        val b1 = bytes[1].toUByte().toUInt()
        val b2 = bytes[2].toUByte().toUInt()
        val b3 = bytes[3].toUByte().toUInt()

        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

/**
 * Safe URL path component appending — replaces URLByAppendingPathComponent(x)!!
 */
private fun NSURL.safeAppend(component: String): NSURL =
    URLByAppendingPathComponent(component)
        ?: throw IllegalStateException("Failed to construct URL: base='$path' component='$component'")

/**
 * Custom exception for queue corruption
 */
class CorruptQueueException(message: String) : Exception(message)
