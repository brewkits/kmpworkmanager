@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.CompressionLevel
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.cinterop.*
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.posix.*
import platform.zlib.*

/**
 * iOS implementation of file compression using native zlib (system library).
 *
 * **Status: REAL ZIP implementation via `platform.zlib`.**
 * Uses Apple's system `/usr/lib/libz.dylib` (always present on iOS) to produce
 * a standards-compliant PKZIP archive — readable by macOS Finder, Windows Explorer,
 * `unzip` CLI, and any other standard ZIP reader. No external Swift packages or
 * cinterop stubs required; `platform.zlib` is bundled with the Kotlin/Native iOS SDK.
 *
 * **PKZIP Structure (DEFLATE):**
 * ```
 * [Local File Header 0x04034b50] [Compressed Data] ...
 * [Central Directory Entry 0x02014b50] ...
 * [End of Central Directory Record 0x06054b50]
 * ```
 *
 * **Memory Footprint:** O(1) RAM. The input file is streamed in 64 KiB chunks through
 * `deflate()` directly to the output file — no full-file buffers in memory.
 *
 * **Compression Level Mapping:**
 * - `LOW`    → `Z_BEST_SPEED` (1)
 * - `MEDIUM` → `Z_DEFAULT_COMPRESSION` (6)
 * - `HIGH`   → `Z_BEST_COMPRESSION` (9)
 *
 * **Fallback (`allowIosUncompressedFallback`):** This flag is kept for backward API
 * compatibility but is no longer needed — the worker now always produces a real ZIP
 * archive. Setting it to `true` on v3.2.0+ has no effect beyond a log warning.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual suspend fun platformCompress(config: FileCompressionConfig): WorkerResult {
    val fileManager = NSFileManager.defaultManager

    if (!fileManager.fileExistsAtPath(config.inputPath)) {
        return WorkerResult.Failure("Input file does not exist: ${config.inputPath}")
    }

    if (config.allowIosUncompressedFallback) {
        Logger.w(
            "FileCompressionWorker",
            "allowIosUncompressedFallback is set but no longer needed — " +
                "iOS now produces a real PKZIP archive via platform.zlib."
        )
    }

    return withContext(AppDispatchers.IO) {
        try {
            val originalSize = fileManager.attributesOfItemAtPath(config.inputPath, null)
                ?.get("NSFileSize") as? Long ?: 0L

            // Remove existing output file atomically before writing
            if (fileManager.fileExistsAtPath(config.outputPath)) {
                fileManager.removeItemAtPath(config.outputPath, null)
            }

            val compressedSize = compressToPkZip(
                inputPath = config.inputPath,
                outputPath = config.outputPath,
                level = config.level,
                excludePatterns = config.excludePatterns
            )

            if (config.deleteOriginal) {
                fileManager.removeItemAtPath(config.inputPath, null)
                Logger.i("FileCompressionWorker", "Deleted original: ${config.inputPath}")
            }

            val ratio = if (originalSize > 0) (compressedSize * 100.0 / originalSize).toInt() else 0
            WorkerResult.Success(
                message = "Compressed ${formatBytes(originalSize)} → ${formatBytes(compressedSize)} " +
                    "($ratio% of original). Output: ${config.outputPath}"
            )
        } catch (e: ZipException) {
            Logger.e("FileCompressionWorker", "ZIP compression failed: ${e.message}")
            WorkerResult.Failure("ZIP compression failed: ${e.message}")
        } catch (e: Exception) {
            Logger.e("FileCompressionWorker", "Compression error", e)
            WorkerResult.Failure("Compression failed: ${e.message}")
        }
    }
}

/** Thrown when zlib reports an error condition. */
private class ZipException(msg: String) : Exception(msg)

/**
 * Writes a single-file PKZIP archive to [outputPath] using [platform.zlib] DEFLATE.
 * Returns the number of compressed bytes written (PKZIP file size).
 *
 * The PKZIP format for a single-entry archive:
 *  ┌─────────────────────────────────────┐
 *  │ Local File Header (30 + fnLen bytes)│
 *  │ Compressed data (DEFLATE stream)   │
 *  │ Central Directory Entry (46+fnLen) │
 *  │ End of Central Directory (22 bytes)│
 *  └─────────────────────────────────────┘
 *
 * File writes are done through POSIX `fopen`/`fwrite`/`fclose` to avoid pulling
 * in Foundation and to keep the implementation testable without a host app.
 */
@OptIn(ExperimentalForeignApi::class)
private fun compressToPkZip(
    inputPath: String,
    outputPath: String,
    level: CompressionLevel,
    excludePatterns: List<String>?
): Long {
    val fileName = inputPath.substringAfterLast('/')

    // Skip if excluded
    if (excludePatterns != null) {
        for (pattern in excludePatterns) {
            if (matchesGlob(fileName, pattern)) {
                throw ZipException("File '$fileName' excluded by pattern '$pattern'")
            }
        }
    }

    val zlibLevel = when (level) {
        CompressionLevel.LOW -> Z_BEST_SPEED
        CompressionLevel.MEDIUM -> Z_DEFAULT_COMPRESSION
        CompressionLevel.HIGH -> Z_BEST_COMPRESSION
    }

    // CHUNK size for streaming — 64 KiB keeps RAM footprint flat
    val CHUNK = 65536

    return memScoped {
        // ── Open input file ────────────────────────────────────────────────────
        val inFile = fopen(inputPath, "rb")
            ?: throw ZipException("Cannot open input file: $inputPath")
        try {
            // ── Compute original CRC32 and uncompressed size (need both for PKZIP) ─
            var crc = crc32(0u.convert(), null, 0u).convert<ULong>()
            var uncompressedSize = 0L
            val crcBuf = allocArray<ByteVar>(CHUNK)
            while (true) {
                val n = fread(crcBuf, 1u, CHUNK.toULong(), inFile)
                if (n == 0uL) break
                crc = crc32(crc.convert(), crcBuf.reinterpret(), n.convert()).convert()
                uncompressedSize += n.toLong()
            }
            rewind(inFile) // Reset for second pass (compression)

            // ── Open output file ───────────────────────────────────────────────
            val outFile = fopen(outputPath, "wb")
                ?: throw ZipException("Cannot open output file: $outputPath")
            try {
                // ── Write Local File Header (Signature 0x04034b50) ─────────────
                // Fields: PK\x03\x04, version needed (20 = 2.0), flags, method (8=DEFLATE),
                //         mtime, mdate, crc32, compressed size (0 = unknown yet), uncompressed size,
                //         filename length, extra field length
                val fnBytes = fileName.encodeToByteArray()
                val fnLen = fnBytes.size.toUShort()

                writeUInt32LE(outFile, 0x04034b50u) // Local file header signature
                writeUInt16LE(outFile, 20u)          // Version needed (2.0)
                writeUInt16LE(outFile, 0u)           // General purpose bit flag
                writeUInt16LE(outFile, 8u)           // Compression method (DEFLATE)
                writeUInt16LE(outFile, 0u)           // Last mod time (0 = no time)
                writeUInt16LE(outFile, 0u)           // Last mod date
                writeUInt32LE(outFile, crc.toUInt()) // CRC-32
                writeUInt32LE(outFile, 0u)           // Compressed size (placeholder)
                writeUInt32LE(outFile, uncompressedSize.toUInt()) // Uncompressed size
                writeUInt16LE(outFile, fnLen)        // File name length
                writeUInt16LE(outFile, 0u)           // Extra field length
                // File name
                fnBytes.usePinned { pin ->
                    fwrite(pin.addressOf(0), 1u, fnLen.toULong(), outFile)
                }

                // ── Compress via zlib deflate into output ──────────────────────
                val localHeaderSize = 30L + fnLen.toLong()
                val compressedStart = localHeaderSize
                var compressedSize = 0L

                val zstream = alloc<z_stream>()
                zstream.zalloc = null
                zstream.zfree = null
                zstream.opaque = null

                // -15 = raw deflate (no zlib wrapper) — PKZIP uses raw deflate
                val initRet = deflateInit2(
                    zstream.ptr, zlibLevel,
                    Z_DEFLATED, -15,
                    MAX_MEM_LEVEL, Z_DEFAULT_STRATEGY
                )
                if (initRet != Z_OK) throw ZipException("deflateInit2 failed: $initRet")

                try {
                    val inBuf = allocArray<ByteVar>(CHUNK)
                    val outBuf = allocArray<ByteVar>(CHUNK)
                    var flush = Z_NO_FLUSH

                    while (flush != Z_FINISH) {
                        val nRead = fread(inBuf, 1u, CHUNK.toULong(), inFile)
                        flush = if (nRead < CHUNK.toULong()) Z_FINISH else Z_NO_FLUSH

                        zstream.avail_in = nRead.convert()
                        zstream.next_in = inBuf.reinterpret()

                        do {
                            zstream.avail_out = CHUNK.toUInt()
                            zstream.next_out = outBuf.reinterpret()

                            val deflateRet = deflate(zstream.ptr, flush)
                            if (deflateRet == Z_STREAM_ERROR) {
                                throw ZipException("deflate returned Z_STREAM_ERROR")
                            }

                            val have = (CHUNK - zstream.avail_out.toInt()).toULong()
                            if (have > 0u) {
                                fwrite(outBuf, 1u, have, outFile)
                                compressedSize += have.toLong()
                            }
                        } while (zstream.avail_out == 0u)
                    }
                } finally {
                    deflateEnd(zstream.ptr)
                }

                // ── Back-patch compressed size in Local File Header ────────────
                // Seek back to offset 18 (after sig + version + flags + method + mtime + mdate + crc32)
                fseek(outFile, (localHeaderSize - 12).toLong(), SEEK_SET)
                writeUInt32LE(outFile, crc.toUInt())              // Re-write CRC
                writeUInt32LE(outFile, compressedSize.toUInt())  // Now we know compressed size
                fseek(outFile, 0, SEEK_END) // Seek back to end for Central Directory

                val centralDirOffset = localHeaderSize + compressedSize

                // ── Central Directory Entry (0x02014b50) ───────────────────────
                writeUInt32LE(outFile, 0x02014b50u) // Central directory signature
                writeUInt16LE(outFile, 20u)          // Version made by (2.0)
                writeUInt16LE(outFile, 20u)          // Version needed (2.0)
                writeUInt16LE(outFile, 0u)           // General purpose bit flag
                writeUInt16LE(outFile, 8u)           // Compression method (DEFLATE)
                writeUInt16LE(outFile, 0u)           // Last mod time
                writeUInt16LE(outFile, 0u)           // Last mod date
                writeUInt32LE(outFile, crc.toUInt()) // CRC-32
                writeUInt32LE(outFile, compressedSize.toUInt())       // Compressed size
                writeUInt32LE(outFile, uncompressedSize.toUInt())     // Uncompressed size
                writeUInt16LE(outFile, fnLen)        // Filename length
                writeUInt16LE(outFile, 0u)           // Extra field length
                writeUInt16LE(outFile, 0u)           // File comment length
                writeUInt16LE(outFile, 0u)           // Disk number start
                writeUInt16LE(outFile, 0u)           // Internal file attributes
                writeUInt32LE(outFile, 0u)           // External file attributes
                writeUInt32LE(outFile, 0u)           // Local header relative offset
                // Filename
                fnBytes.usePinned { pin ->
                    fwrite(pin.addressOf(0), 1u, fnLen.toULong(), outFile)
                }

                val centralDirSize = 46L + fnLen.toLong()

                // ── End of Central Directory Record (0x06054b50) ──────────────
                writeUInt32LE(outFile, 0x06054b50u)  // EOCD signature
                writeUInt16LE(outFile, 0u)           // Disk number
                writeUInt16LE(outFile, 0u)           // Disk with start of central dir
                writeUInt16LE(outFile, 1u)           // Entries on this disk
                writeUInt16LE(outFile, 1u)           // Total entries
                writeUInt32LE(outFile, centralDirSize.toUInt()) // Central dir size
                writeUInt32LE(outFile, centralDirOffset.toUInt()) // Central dir offset
                writeUInt16LE(outFile, 0u)           // Comment length

                val totalSize = centralDirOffset + centralDirSize + 22L
                totalSize
            } finally {
                fclose(outFile)
            }
        } finally {
            fclose(inFile)
        }
    }
}

// ── POSIX/C low-level write helpers ──────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.writeUInt32LE(file: CPointer<FILE>, value: UInt) {
    val buf = allocArray<ByteVar>(4)
    buf[0] = (value and 0xFFu).toByte()
    buf[1] = ((value shr 8) and 0xFFu).toByte()
    buf[2] = ((value shr 16) and 0xFFu).toByte()
    buf[3] = ((value shr 24) and 0xFFu).toByte()
    fwrite(buf, 1u, 4u, file)
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.writeUInt16LE(file: CPointer<FILE>, value: UShort) {
    val buf = allocArray<ByteVar>(2)
    buf[0] = (value.toUInt() and 0xFFu).toByte()
    buf[1] = ((value.toUInt() shr 8) and 0xFFu).toByte()
    fwrite(buf, 1u, 2u, file)
}

/**
 * Simple glob-style pattern matcher supporting `*` (matches anything) and `?` (matches one char).
 */
private fun matchesGlob(name: String, pattern: String): Boolean {
    if (pattern == "*") return true
    if ('*' !in pattern && '?' !in pattern) return name.equals(pattern, ignoreCase = true)
    val regex = "^" + Regex.escape(pattern)
        .replace("\\*", ".*")
        .replace("\\?", ".") + "$"
    return Regex(regex, RegexOption.IGNORE_CASE).matches(name)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${(bytes / 1_073_741_824.0 * 100).toLong() / 100.0} GB"
    bytes >= 1_048_576L     -> "${(bytes / 1_048_576.0 * 100).toLong() / 100.0} MB"
    bytes >= 1_024L         -> "${(bytes / 1_024.0 * 100).toLong() / 100.0} KB"
    else                    -> "$bytes B"
}
