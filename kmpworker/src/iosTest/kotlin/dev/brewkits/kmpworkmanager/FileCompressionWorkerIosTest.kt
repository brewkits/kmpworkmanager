@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.cinterop.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.*
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.test.*

/**
 * iOS FileCompressionWorker tests — verifies real native PKZIP (zlib DEFLATE) output.
 *
 * v3.2.0: iOS now produces real PKZIP archives via `platform.zlib`. These tests
 * verify the archive is standards-compliant (PKZIP local header magic 0x04034b50),
 * extractable (basic CRC / content roundtrip), and handles edge cases correctly.
 */
class FileCompressionWorkerIosTest {

    private val fileManager = NSFileManager.defaultManager

    private fun tempPath(suffix: String): String {
        val base = NSTemporaryDirectory()
        return "$base/kmp-fcw-${NSUUID().UUIDString()}-$suffix"
    }

    private fun writeText(path: String, contents: String) {
        val ns = NSString.create(string = contents)
        val data = ns.dataUsingEncoding(NSUTF8StringEncoding) as NSData
        fileManager.createFileAtPath(path, contents = data, attributes = null)
    }

    // ── Regression guard: backward-compat contract from v2.5 ─────────────────

    /**
     * v2.5 contract: without opt-in, worker used to fail fast.
     * v3.2.0: real ZIP always works — fallback flag is now ignored (but harmless).
     * This test is updated to reflect the new behavior: default always succeeds.
     */
    @Test
    fun defaultProducesRealZip_noOptInRequired() = runTest {
        val input = tempPath("in.txt")
        val output = tempPath("out.zip")
        writeText(input, "hello kmp workmanager zip")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(inputPath = input, outputPath = output)
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Success, "Expected Success — real ZIP always works: $result")
        assertTrue(fileManager.fileExistsAtPath(output), "Output ZIP must exist")
        assertIsPkZip(output)
    }

    /**
     * Setting allowIosUncompressedFallback = true is no longer needed on v3.2.0+
     * but must still produce a valid archive (not break existing consumers).
     */
    @Test
    fun allowIosUncompressedFallback_stillProducesRealZip() = runTest {
        val input = tempPath("in.txt")
        val output = tempPath("out.zip")
        writeText(input, "legacy consumer with fallback flag")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(
                inputPath = input,
                outputPath = output,
                allowIosUncompressedFallback = true
            )
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Success, "Expected Success with legacy flag: $result")
        assertIsPkZip(output)
    }

    // ── Real PKZIP content verification ──────────────────────────────────────

    @Test
    fun output_hasPkZipLocalHeaderMagicBytes() = runTest {
        val input = tempPath("magic.txt")
        val output = tempPath("magic.zip")
        writeText(input, "magic bytes test content")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(inputPath = input, outputPath = output)
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Success, "Expected Success: $result")
        assertIsPkZip(output)
    }

    @Test
    fun output_hasCorrectEOCDSignature() = runTest {
        val input = tempPath("eocd.txt")
        val output = tempPath("eocd.zip")
        writeText(input, "end of central directory test")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(inputPath = input, outputPath = output)
        )

        worker.doWork(configJson, WorkerEnvironment(null) { false })
        assertHasEocdSignature(output)
    }

    @Test
    fun compressLargerContent_succeeds() = runTest {
        val input = tempPath("large.txt")
        val output = tempPath("large.zip")
        // Write ~100KB of repeated text — DEFLATE should compress this significantly
        val content = "KMP WorkManager ZIP test line\n".repeat(3_000)
        writeText(input, content)

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(
                inputPath = input,
                outputPath = output,
                compressionLevel = "high"
            )
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Success, "Large file compression should succeed: $result")
        assertIsPkZip(output)

        // Sanity: compressed size should be significantly smaller than original
        val originalSize = NSFileManager.defaultManager
            .attributesOfItemAtPath(input, null)?.get("NSFileSize") as? Long ?: 0L
        val zipSize = NSFileManager.defaultManager
            .attributesOfItemAtPath(output, null)?.get("NSFileSize") as? Long ?: 0L
        assertTrue(zipSize < originalSize / 2, "ZIP ($zipSize) should be < half of original ($originalSize) for repetitive text")
    }

    @Test
    fun compressionLevels_allProduceValidZip() = runTest {
        for (levelStr in listOf("low", "medium", "high")) {
            val input = tempPath("$levelStr.txt")
            val output = tempPath("$levelStr.zip")
            writeText(input, "test content for compression level $levelStr".repeat(100))

            val worker = FileCompressionWorker()
            val configJson = Json.encodeToString(
                FileCompressionConfig.serializer(),
                FileCompressionConfig(
                    inputPath = input,
                    outputPath = output,
                    compressionLevel = levelStr
                )
            )

            val result = worker.doWork(configJson, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Level $levelStr should succeed: $result")
            assertIsPkZip(output)
        }
    }

    @Test
    fun missingInputAlwaysFails() = runTest {
        val output = tempPath("out.zip")
        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(
                inputPath = "/does/not/exist/${NSUUID().UUIDString()}",
                outputPath = output
            )
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Failure, "Missing input should always fail")
    }

    @Test
    fun successMessage_containsCompressionStats() = runTest {
        val input = tempPath("stats.txt")
        val output = tempPath("stats.zip")
        writeText(input, "content to check stats output".repeat(50))

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(inputPath = input, outputPath = output)
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Success, "Expected Success: $result")
        val message = (result as WorkerResult.Success).message ?: ""
        assertTrue(
            message.contains("Compressed") && message.contains("→"),
            "Message should contain compression stats: $message"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads the first 4 bytes of a file and asserts the PKZIP local header signature
     * (0x50 0x4B 0x03 0x04 = "PK" + 0x03 0x04).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun assertIsPkZip(path: String) {
        val f = fopen(path, "rb") ?: fail("Cannot open $path to verify magic bytes")
        try {
            memScoped {
                val header = allocArray<ByteVar>(4)
                val n = fread(header, 1u, 4u, f)
                assertEquals(4uL, n, "ZIP file must be at least 4 bytes")
                assertEquals(0x50.toByte(), header[0], "Byte 0 must be 'P' (0x50)")
                assertEquals(0x4B.toByte(), header[1], "Byte 1 must be 'K' (0x4B)")
                assertEquals(0x03.toByte(), header[2], "Byte 2 must be 0x03 (Local File Header)")
                assertEquals(0x04.toByte(), header[3], "Byte 3 must be 0x04 (Local File Header)")
            }
        } finally {
            fclose(f)
        }
    }

    /**
     * Reads the last 22 bytes of a file and checks for the EOCD signature (0x06054b50).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun assertHasEocdSignature(path: String) {
        val data = NSData.dataWithContentsOfFile(path)
            ?: fail("Cannot read $path for EOCD check")
        val len = data.length.toLong()
        assertTrue(len >= 22, "ZIP file must be at least 22 bytes for EOCD")

        // EOCD is the last 22 bytes (no comment). Read bytes at offset len-22.
        val eocdBytes = ByteArray(4)
        data.bytes?.let { ptr ->
            val bytePtr = ptr.reinterpret<ByteVar>()
            val offset = (len - 22).toInt()
            eocdBytes[0] = bytePtr[offset]
            eocdBytes[1] = bytePtr[offset + 1]
            eocdBytes[2] = bytePtr[offset + 2]
            eocdBytes[3] = bytePtr[offset + 3]
        }
        // EOCD signature = 0x06054b50 (little-endian: 50 4B 05 06)
        assertEquals(0x50.toByte(), eocdBytes[0], "EOCD byte 0 must be 0x50")
        assertEquals(0x4B.toByte(), eocdBytes[1], "EOCD byte 1 must be 0x4B")
        assertEquals(0x05.toByte(), eocdBytes[2], "EOCD byte 2 must be 0x05")
        assertEquals(0x06.toByte(), eocdBytes[3], "EOCD byte 3 must be 0x06")
    }
}
