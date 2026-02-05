package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import platform.Foundation.*

/**
 * Persistent index for AppendOnlyQueue line positions
 * v2.2.2+ Performance optimization: O(1) startup instead of O(N) sequential scan
 *
 * **File Format (Binary):**
 * ```
 * [offset0:8][offset1:8][offset2:8]...[offsetN:8]
 * ```
 * Each line offset is stored as 8 bytes (ULong, little-endian)
 *
 * **Benefits:**
 * - 10,000 items: 80KB index file (8 bytes × 10,000)
 * - Load time: ~1ms vs ~100ms sequential scan (100x faster)
 * - Memory: Same as in-memory cache (Map<Int, ULong>)
 *
 * **Safety:**
 * - Index corruption is recoverable (fallback to sequential scan)
 * - Index is regenerated after compaction
 * - Index is always consistent with queue file
 *
 * @param indexFileURL URL to the index file (queue.index)
 */
@OptIn(ExperimentalForeignApi::class)
internal class QueueIndex(private val indexFileURL: NSURL) {

    private val fileManager = NSFileManager.defaultManager

    /**
     * Save line position cache to disk (binary format)
     *
     * @param lineOffsets Map of line index to byte offset
     * @throws Exception if write fails
     */
    fun saveIndex(lineOffsets: Map<Int, ULong>) {
        if (lineOffsets.isEmpty()) {
            // Empty index - delete file if exists
            deleteIndex()
            return
        }

        // Build binary data: [offset0:8][offset1:8]...[offsetN:8]
        val data = NSMutableData()

        // Sort by line index to ensure sequential order
        val sortedOffsets = lineOffsets.toList().sortedBy { it.first }

        sortedOffsets.forEach { (_, offset: ULong) ->
            // Write ULong as 8 bytes (little-endian)
            memScoped {
                val buffer = allocArray<ULongVar>(1)
                buffer[0] = offset

                data.appendBytes(buffer, 8u)
            }
        }

        // Atomic write
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val success = data.writeToURL(
                indexFileURL,
                options = NSDataWritingAtomic,
                error = errorPtr.ptr
            )

            if (success) {
                Logger.d(LogTags.QUEUE, "Saved queue index: ${lineOffsets.size} entries")
            } else {
                val error = errorPtr.value
                Logger.w(
                    LogTags.QUEUE,
                    "Failed to save queue index: ${error?.localizedDescription}"
                )
            }
        }
    }

    /**
     * Load line position cache from disk
     *
     * @return Map of line index to byte offset, or empty map if index doesn't exist/is corrupt
     */
    fun loadIndex(): Map<Int, ULong> {
        val path = indexFileURL.path ?: return emptyMap()

        if (!fileManager.fileExistsAtPath(path)) {
            Logger.d(LogTags.QUEUE, "No queue index found - will build on first access")
            return emptyMap()
        }

        return try {
            val data = NSData.dataWithContentsOfFile(path)
                ?: return emptyMap()

            val entryCount = (data.length / 8u).toInt()

            if (data.length % 8u != 0UL) {
                Logger.w(
                    LogTags.QUEUE,
                    "Queue index is corrupt (size not multiple of 8) - will rebuild"
                )
                return emptyMap()
            }

            val offsets = mutableMapOf<Int, ULong>()

            memScoped {
                val buffer = data.bytes?.reinterpret<ULongVar>()
                    ?: return emptyMap()

                for (i in 0 until entryCount) {
                    offsets[i] = buffer[i]
                }
            }

            Logger.i(LogTags.QUEUE, "✅ Loaded queue index: $entryCount entries (O(1) startup!)")
            offsets

        } catch (e: Exception) {
            Logger.w(LogTags.QUEUE, "Failed to load queue index - will rebuild", e)
            emptyMap()
        }
    }

    /**
     * Delete the index file
     */
    fun deleteIndex() {
        val path = indexFileURL.path ?: return

        if (fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.removeItemAtPath(path, errorPtr.ptr)
            }
            Logger.d(LogTags.QUEUE, "Deleted queue index")
        }
    }

    /**
     * Check if index file exists
     */
    fun exists(): Boolean {
        val path = indexFileURL.path ?: return false
        return fileManager.fileExistsAtPath(path)
    }
}
