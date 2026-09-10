@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.crc32
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.create
import platform.Foundation.getBytes
import platform.Foundation.offsetInFile
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeData

/**
 * Reads and writes the queue file's record format, and nothing else.
 *
 * Stage D1 of the `AppendOnlyQueue` split. That class had grown to ~1,845 lines carrying seven
 * unrelated responsibilities, and this is the seam every other one sits on: compaction,
 * corruption recovery, line counting and the queue API all ultimately go through these five
 * functions. Extracting it first is the same order that worked for `IosFileStorage` in this
 * release — take out the shared primitive, then the stores that use it.
 *
 * Two record formats live here:
 *
 * - **Binary** (`FORMAT_VERSION`): `[length:4][data:length][crc32:4][\n:1]`, the current format.
 * - **Legacy text**: newline-delimited JSON, still read so that queues written before the
 *   binary format can be migrated rather than discarded.
 *
 * ### On the corruption callback
 *
 * The two read paths are the only place corruption is *detected*, but the flag and offset that
 * record it belong to the queue — `dequeue()` reads them without a lock and they are `@Volatile`
 * for that reason. Rather than move that state here, or hand this class a reference back to the
 * queue, detection is reported through [onCorruption] and the queue decides what to do with it.
 * This class stays free of shared mutable state, which is what makes it testable in isolation.
 *
 * @param onCorruption invoked with the byte offset of the record that failed to parse or whose
 *   CRC did not match. Called before the read returns `null`.
 */
internal class QueueRecordCodec(
    private val onCorruption: (offset: ULong) -> Unit
) {

    /**
     * Read a single line from file handle at current position
     */
    // Suppressions here and on readSingleRecordWithValidation: both are verbatim moves whose
    // findings were carried in kmpworker's detekt baseline at their old location, and a
    // baseline keys on declaration text. The shapes are load-bearing — the nesting is the
    // chunked read loop, and catch(Exception) is how a malformed record is turned into a
    // corruption report rather than an escaping failure mid-dequeue.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    fun readSingleLine(fileHandle: NSFileHandle): String? {
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
            onCorruption(lineStartOffset)
            return null
        }
    }

    /**
     * Write binary file header (magic number + version)
     */
    fun writeFileHeader(fileHandle: NSFileHandle) {
        // Write magic number (4 bytes)
        fileHandle.writeData(MAGIC_NUMBER.toByteArray().toNSData())

        // Write format version (4 bytes)
        fileHandle.writeData(FORMAT_VERSION.toByteArray().toNSData())
    }

    /**
     * Append item to queue file in binary format with CRC32
     * Format: [length:4][data:length][crc32:4][\n:1]
     *
     * Combined into a single write to reduce memory pinning overhead.
     */
    fun appendToQueueFileBinary(fileHandle: NSFileHandle, item: String) {
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
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun readSingleRecordWithValidation(fileHandle: NSFileHandle): String? {
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
                Logger.e(
                    LogTags.QUEUE,
                    "CRC mismatch! Expected: ${expectedCrc.toString(16)}, " +
                        "Actual: ${actualCrc.toString(16)}"
                )
                throw CorruptQueueException("CRC32 validation failed")
            }

            jsonBytes.decodeToString()

        } catch (e: CorruptQueueException) {
            Logger.e(LogTags.QUEUE, "Corrupt binary record detected at offset $recordStartOffset", e)
            onCorruption(recordStartOffset)
            return null
        } catch (e: Exception) {
            Logger.e(LogTags.QUEUE, "Error reading binary record at offset $recordStartOffset", e)
            onCorruption(recordStartOffset)
            return null
        }
    }





    private companion object {
        /** "KMPQ" in ASCII — first four bytes of a binary-format queue file. */
        const val MAGIC_NUMBER: UInt = 0x4B4D5051u
        const val FORMAT_VERSION: UInt = 0x00000001u
        const val LEGACY_READ_CHUNK_SIZE: Int = 4096
    }
}

    /**
 * Read UInt from bytes (Little Endian)
 */
internal fun readUIntFromBytes(bytes: CPointer<ByteVar>): UInt {
    val b0 = bytes[0].toUByte().toUInt()
    val b1 = bytes[1].toUByte().toUInt()
    val b2 = bytes[2].toUByte().toUInt()
    val b3 = bytes[3].toUByte().toUInt()

    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

    /**
 * Convert UInt to ByteArray (Little Endian)
 */
internal fun UInt.toByteArray(): ByteArray {
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
internal fun ByteArray.toNSData(): NSData {
    if (this.isEmpty()) return NSData()
    return this.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
    }
}

    /**
 * String to NSData conversion helper
 */
internal fun String.toNSData(): NSData {
    val bytes = this.encodeToByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}

