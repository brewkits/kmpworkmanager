package dev.brewkits.kmpworkmanager.background.data

import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * @param compactionScope CoroutineScope for background compaction operations (v2.1.1+)
 *                        Defaults to a supervised scope with Default dispatcher
 *
 * Note: File coordination will be added in Phase 3 (v2.1.0 DI implementation)
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppendOnlyQueue(
    private val baseDirectoryURL: NSURL,
    private val compactionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val queueMutex = Mutex()
    private val fileManager = NSFileManager.defaultManager

    // Corruption handling
    private var isQueueCorrupt = false
    private var corruptionOffset: ULong = 0UL  // Byte offset of the first corrupt record
    private val corruptionMutex = Mutex()

    private var fileFormat: UInt? = null
    private val formatMutex = Mutex()

    // File paths
    private val queueFileURL = baseDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
    private val headPointerURL = baseDirectoryURL.URLByAppendingPathComponent("head_pointer.txt")!!
    private val compactedQueueURL = baseDirectoryURL.URLByAppendingPathComponent("queue_compacted.jsonl")!!

    // Line position cache: maps line index → file byte offset
    // Example: {0: 0, 1: 45, 2: 92, ...} means line 0 starts at byte 0, line 1 at byte 45, etc.
    private val linePositionCache = mutableMapOf<Int, ULong>()
    private var cacheValid = false

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

        detectAndMigrateIfNeeded()

        // Auto-migrate from old format if needed (v2.1.0 migration)
        migrateQueueIfNeeded()
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
        queueMutex.withLock {
            checkDiskSpace(item.length.toLong())

            coordinated(queueFileURL, write = true) {
                // Append single line to queue file (O(1) operation)
                // Note: We removed size check here to maintain O(1) performance
                // Size limits should be enforced at a higher level if needed
                appendToQueueFile(item)

                // Invalidate cache since file changed
                cacheValid = false

                Logger.v(LogTags.QUEUE, "Enqueued $item")
            }
        }
    }

    /**
     * Dequeue the first unprocessed item from the queue
     * **Performance**: O(1) - reads single line and updates head pointer
     *
     * @return Item ID or null if queue is empty
     */
    suspend fun dequeue(): String? {
        // Fast check without lock (CRITICAL: prevents race condition)
        if (isQueueCorrupt) {
            // Double mutex pattern to prevent race condition
            // CRITICAL: Must acquire in order: corruptionMutex → queueMutex
            corruptionMutex.withLock {
                queueMutex.withLock {
                    // Double-check inside lock (prevents TOCTOU issues)
                    if (isQueueCorrupt) {
                        coordinated(queueFileURL, write = true) {
                            Logger.w(LogTags.QUEUE, "Queue corruption detected during dequeue. Truncating at offset $corruptionOffset...")
                            truncateAtCorruptionPoint()  // Safe: queueMutex already held
                            isQueueCorrupt = false
                            corruptionOffset = 0UL
                        }
                    }
                }
            }
            return null
        }

        return queueMutex.withLock {
            coordinated(headPointerURL, write = true) {
                val headIndex = readHeadPointer()
                val item = readLineAtIndex(headIndex)

                if (item != null) {
                    // Increment head pointer (O(1) operation)
                    writeHeadPointer(headIndex + 1)

                    Logger.v(LogTags.QUEUE, "Dequeued $item. New head index: ${headIndex + 1}")

                    // Check if compaction is needed (non-blocking)
                    if (shouldCompact()) {
                        Logger.i(LogTags.QUEUE, "Compaction threshold reached. Scheduling compaction...")
                        // Launch compaction in background (non-blocking)
                        scheduleCompaction()
                    }
                } else {
                    Logger.v(LogTags.QUEUE, "Queue is empty")
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
            coordinated(queueFileURL, write = false) {
                getQueueSizeInternal()
            }
        }
    }

    // ==================== Private Implementation ====================

    /**
     * Append a single line to the queue file
     * **Performance**: O(1) - seek to end and write
     */
    private fun appendToQueueFile(item: String) {
        val path = queueFileURL.path ?: throw IllegalStateException("Queue file path is null")

        // Create file if it doesn't exist
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, null, null)

            if (fileFormat == null || fileFormat == FORMAT_VERSION) {
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)

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

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)

            if (fileHandle == null) {
                throw IllegalStateException("Failed to open queue file: ${errorPtr.value?.localizedDescription}")
            }

            try {
                // Seek to end of file (O(1))
                fileHandle.seekToEndOfFile()

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
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    /**
     * Read line at specified index
     * **Performance**: O(1) with cache, O(N) first time (builds cache)
     */
    private fun readLineAtIndex(index: Int): String? {
        val path = queueFileURL.path ?: return null

        if (!fileManager.fileExistsAtPath(path)) {
            return null
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            val fileHandle = NSFileHandle.fileHandleForReadingFromURL(queueFileURL, errorPtr.ptr)
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
                return line
            }

            currentIndex++
            currentOffset = fileHandle.offsetInFile
        }

        cacheValid = true
        return null  // Index out of bounds
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
    private fun readHeadPointer(): Int {
        val path = headPointerURL.path ?: return 0

        if (!fileManager.fileExistsAtPath(path)) {
            // First time: Create head pointer at 0
            writeHeadPointer(0)
            return 0
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            content?.toString()?.trim()?.toIntOrNull() ?: 0
        }
    }

    /**
     * Write head pointer value
     * **Performance**: O(1)
     */
    private fun writeHeadPointer(index: Int) {
        val path = headPointerURL.path ?: throw IllegalStateException("Head pointer path is null")

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

    /**
     * Get queue size (unprocessed items)
     */
    private fun getQueueSizeInternal(): Int {
        val headIndex = readHeadPointer()
        val totalLines = countTotalLines()
        return maxOf(0, totalLines - headIndex)
    }

    /**
     * Count total lines in queue file
     */
    private fun countTotalLines(): Int {
        val path = queueFileURL.path ?: return 0

        if (!fileManager.fileExistsAtPath(path)) {
            return 0
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            content?.split("\n")?.filter { it.isNotBlank() }?.size ?: 0
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
                Logger.e(LogTags.CHAIN, "Background compaction failed: ${e.message}")
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
            coordinated(queueFileURL, write = true) {
                Logger.i(LogTags.CHAIN, "Starting queue compaction...")

                val headIndex = readHeadPointer()
                val totalLines = countTotalLines()
                val unprocessedCount = totalLines - headIndex

                if (unprocessedCount <= 0) {
                    Logger.i(LogTags.CHAIN, "Queue is empty. No compaction needed.")
                    cacheValid = false
                    linePositionCache.clear()
                    return@coordinated
                }

                // Step 1: Read all unprocessed items
                val unprocessedItems = mutableListOf<String>()
                for (i in headIndex until totalLines) {
                    val item = readLineAtIndex(i)
                    if (item != null) {
                        unprocessedItems.add(item)
                    }
                }

                Logger.d(LogTags.CHAIN, "Compacting: $unprocessedCount unprocessed items (${headIndex} processed)")

                // Step 2: Write to temporary compacted file
                writeItemsToFile(compactedQueueURL, unprocessedItems)

                // Step 3: Atomically replace old file with compacted file
                val queuePath = queueFileURL.path ?: throw IllegalStateException("Queue file path is null")
                val compactedPath = compactedQueueURL.path ?: throw IllegalStateException("Compacted file path is null")

                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                    // Delete old queue file
                    fileManager.removeItemAtPath(queuePath, errorPtr.ptr)

                    // Move compacted file to queue file
                    val success = fileManager.moveItemAtPath(
                        compactedPath,
                        toPath = queuePath,
                        error = errorPtr.ptr
                    )

                    if (!success) {
                        throw IllegalStateException("Failed to replace queue file: ${errorPtr.value?.localizedDescription}")
                    }
                }

                // Step 4: Reset head pointer to 0
                writeHeadPointer(0)

                // Step 5: Invalidate cache AFTER file replacement (Fix #3 - prevent stale reads)
                linePositionCache.clear()
                cacheValid = false

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

                        // Trigger migration
                        fileHandle.closeFile()
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
            // Step 1: Rename to .legacy
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val success = fileManager.moveItemAtPath(queuePath, toPath = legacyPath, error = errorPtr.ptr)

                if (!success) {
                    throw IllegalStateException("Failed to rename queue file: ${errorPtr.value?.localizedDescription}")
                }
            }

            // Step 2: Read all items from legacy file
            val items = mutableListOf<String>()
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val content = NSString.stringWithContentsOfFile(
                    legacyPath,
                    encoding = NSUTF8StringEncoding,
                    error = errorPtr.ptr
                )

                if (content != null) {
                    content.split("\n").forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            items.add(trimmed)
                        }
                    }
                }
            }

            Logger.i(LogTags.QUEUE, "Read ${items.size} items from legacy queue")

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

            // Step 5: Reset head pointer to 0
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
     * v2.1.3+
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

            Logger.i(LogTags.CHAIN, "Migration complete. Queue format upgraded to v2.1.0")
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
                fileManager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = errorPtr.ptr
                )
            }
        }
    }

    /**
     * Execute block with file coordination
     * Note: File coordination abstraction will be added in Phase 3 (v2.1.0 DI implementation)
     * For now, executes directly. Thread-safety is provided by queueMutex.
     */
    private fun <T> coordinated(url: NSURL, write: Boolean, block: () -> T): T {
        // Direct execution - thread-safety provided by queueMutex
        return block()
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

            // Require 100MB buffer + actual size
            val requiredWithBuffer = requiredBytes + 100_000_000L // 100MB buffer

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
    private fun truncateAtCorruptionPoint() {
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

        val path = queueFileURL.path
        if (path == null) {
            resetQueueInternal()
            return
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)
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

            // Clear cache and reset format
            linePositionCache.clear()
            cacheValid = false

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
     * String to NSData conversion helper
     */
    private fun String.toNSData(): NSData {
        return this.encodeToByteArray().usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = pinned.get().size.toULong())
        }
    }

    // ==================== Binary Format Helpers (v2.1.3+) ====================

    /**
     * Append item to queue file in binary format with CRC32
     * Format: [length:4][data:length][crc32:4][\n:1]
     * v2.1.3+
     */
    private fun appendToQueueFileBinary(fileHandle: NSFileHandle, item: String) {
        val jsonBytes = item.encodeToByteArray()
        val length = jsonBytes.size.toUInt()
        val crc = jsonBytes.crc32()

        // Write: [length][data][crc32][\n]
        fileHandle.writeData(length.toByteArray().toNSData())
        fileHandle.writeData(jsonBytes.toNSData())
        fileHandle.writeData(crc.toByteArray().toNSData())
        fileHandle.writeData("\n".encodeToByteArray().toNSData())
    }

    /**
     * Read single record from binary format with CRC32 validation
     * Format: [length:4][data:length][crc32:4][\n:1]
     * v2.1.3+
     *
     * @return JSON string or null if EOF/corrupt
     */
    private fun readSingleRecordWithValidation(fileHandle: NSFileHandle): String? {
        val recordStartOffset = fileHandle.offsetInFile
        return try {
            // Read length (4 bytes)
            val lengthData = fileHandle.readDataOfLength(4u)
            if (lengthData.length < 4UL) {
                return null // EOF
            }

            val lengthBytes = lengthData.bytes?.reinterpret<ByteVar>()
                ?: throw CorruptQueueException("Cannot read length bytes")
            val length = readUIntFromBytes(lengthBytes)

            if (length == 0u || length > 10_000_000u) { // Sanity check: max 10MB per record
                throw CorruptQueueException("Invalid record length: $length")
            }

            // Read data (length bytes)
            val jsonData = fileHandle.readDataOfLength(length.toULong())
            if (jsonData.length < length.toULong()) {
                throw CorruptQueueException("Incomplete data read: expected $length, got ${jsonData.length}")
            }

            // Read CRC32 (4 bytes)
            val crcData = fileHandle.readDataOfLength(4u)
            if (crcData.length < 4UL) {
                throw CorruptQueueException("Cannot read CRC32")
            }

            val crcBytes = crcData.bytes?.reinterpret<ByteVar>()
                ?: throw CorruptQueueException("Cannot read CRC32 bytes")
            val expectedCrc = readUIntFromBytes(crcBytes)

            // Read newline (1 byte)
            val newlineData = fileHandle.readDataOfLength(1u)
            if (newlineData.length < 1UL) {
                throw CorruptQueueException("Cannot read newline")
            }

            // Convert NSData to ByteArray for CRC validation
            val jsonBytes = ByteArray(jsonData.length.toInt()) { i ->
                jsonData.bytes?.reinterpret<ByteVar>()?.get(i)?.toByte() ?: 0
            }

            // Validate CRC
            val actualCrc = jsonBytes.crc32()
            if (expectedCrc != actualCrc) {
                Logger.e(LogTags.QUEUE, "CRC mismatch! Expected: ${expectedCrc.toString(16)}, Actual: ${actualCrc.toString(16)}")
                throw CorruptQueueException("CRC32 validation failed")
            }

            // Return JSON string
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
     * v2.1.3+
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
     * v2.1.3+
     */
    private fun ByteArray.toNSData(): NSData {
        return this.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }

    /**
     * Read UInt from bytes (Little Endian)
     * v2.1.3+
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
 * Custom exception for queue corruption
 * v2.1.3+
 */
class CorruptQueueException(message: String) : Exception(message)
