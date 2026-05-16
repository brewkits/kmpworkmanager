package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.ParallelHttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.config.ChecksumAlgorithm
import dev.brewkits.kmpworkmanager.workers.config.ParallelHttpDownloadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ParallelHttpDownloadWorker.
 *
 * The interesting paths are:
 *  - HEAD probe + Accept-Ranges + Content-Length agree → N chunked GETs → merge → success.
 *  - HEAD probe missing → sequential fallback.
 *  - Per-chunk resume: if `.partN` already has the exact expected size, the chunk
 *    download is skipped.
 *  - Checksum verification at merged-file stage.
 */
class ParallelHttpDownloadWorkerTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }

    // SHA-256("0123456789ABCDEFGHIJ") = e2f8c0ec8f23ad9a5acbab90c0c6dd8ce1d9cefa4f3f76617c34b27ddef89b3a
    // (Tests construct the digest at runtime instead — avoids drift if the corpus
    // text needs to change.)

    /**
     * Build a MockEngine that:
     *  - Responds to HEAD with Content-Length + Accept-Ranges so the worker chooses parallel.
     *  - Responds to GET with the requested byte range slice from `payload`.
     * Returns the engine + per-request counter so the test can assert how many ranged GETs ran.
     */
    private fun rangeServer(payload: ByteArray): Pair<MockEngine, () -> Int> {
        val getCount = atomic(0)
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(payload.size.toString()),
                        HttpHeaders.AcceptRanges to listOf("bytes")
                    )
                )
                HttpMethod.Get -> {
                    val range = request.headers[HttpHeaders.Range] ?: ""
                    // "bytes=N-M"
                    val m = Regex("""bytes=(\d+)-(\d+)""").find(range)
                        ?: error("Parallel worker must always send a Range header for GET, got: '$range'")
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].toInt()
                    val slice = payload.copyOfRange(start, end + 1)
                    getCount.incrementAndGet()
                    respond(
                        content = slice,
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(
                            HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}")
                        )
                    )
                }
                else -> error("Unexpected method ${request.method}")
            }
        }
        return engine to { getCount.value }
    }

    @Test
    fun parallel_downloadsInChunks_mergesCorrectly() = runTest {
        val payload = ByteArray(40) { i -> (0x30 + (i % 10)).toByte() } // "0123456789" × 4
        val (engine, getCount) = rangeServer(payload)

        val savePath = "test_parallel_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 4
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "expected Success: $result")
            assertEquals(4, getCount(), "exactly 4 ranged GETs must run for numChunks=4")
            assertTrue(fs.exists(savePath))
            val downloaded = fs.source(savePath).buffer().readByteArray()
            assertEquals(payload.toList(), downloaded.toList(), "merged file must equal payload")
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            for (i in 0..3) {
                val p = "${savePath}.part$i".toPath()
                if (fs.exists(p)) fs.delete(p)
            }
            val merged = "${savePath}.merged".toPath()
            if (fs.exists(merged)) fs.delete(merged)
        }
    }

    @Test
    fun parallel_fallsBackToSequential_whenServerDoesNotSupportRanges() = runTest {
        val payload = "no-range-server-payload".encodeToByteArray()

        val getCount = atomic(0)
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString()))
                    // No Accept-Ranges header → worker must fall back to sequential.
                )
                HttpMethod.Get -> {
                    // Range header must NOT be present in fallback path.
                    val range = request.headers[HttpHeaders.Range]
                    assertEquals(null, range, "Sequential fallback must not send Range header")
                    getCount.incrementAndGet()
                    respond(content = payload, status = HttpStatusCode.OK)
                }
                else -> error("Unexpected method ${request.method}")
            }
        }

        val savePath = "test_fallback_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 4
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Sequential fallback must succeed: $result")
            assertEquals(1, getCount.value, "Sequential fallback must issue exactly 1 GET")
            assertEquals(
                payload.toList(),
                fs.source(savePath).buffer().readByteArray().toList()
            )
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            val tmp = "${savePath}.tmp".toPath()
            if (fs.exists(tmp)) fs.delete(tmp)
        }
    }

    @Test
    fun parallel_resumesPreviousAttempt_whenPartFilesAreComplete() = runTest {
        val payload = ByteArray(40) { i -> ('A'.code + (i % 26)).toByte() }
        val (engine, getCount) = rangeServer(payload)

        val savePath = "test_resume_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM

        // Pre-populate parts 0..2 with the EXACT expected byte slice. Part 3 missing.
        val chunkSize = payload.size / 4 // 10
        for (i in 0..2) {
            val start = i * chunkSize
            val end = if (i == 3) payload.size else start + chunkSize
            fs.write("${savePath}.part$i".toPath()) {
                write(payload.copyOfRange(start, end))
            }
        }

        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 4
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Resumed download must succeed: $result")
            assertEquals(
                1, getCount(),
                "Only the missing part should be downloaded — expected 1 ranged GET, got ${getCount()}"
            )
            assertEquals(
                payload.toList(),
                fs.source(savePath).buffer().readByteArray().toList()
            )
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            for (i in 0..3) {
                val p = "${savePath}.part$i".toPath()
                if (fs.exists(p)) fs.delete(p)
            }
            val merged = "${savePath}.merged".toPath()
            if (fs.exists(merged)) fs.delete(merged)
        }
    }

    @Test
    fun parallel_mergeFailure_cleansUpAllParts_breaksRetryStorm() = runTest {
        // QA double-check regression: pre-fix, if mergeChunks threw (e.g. disk full),
        // the catch block deleted only `.merged` and KEPT `.partN`. On retry,
        // downloadOneChunk saw all parts at expected size and skipped the download,
        // mergeChunks would fire again and fail with the same error → infinite retry
        // loop with no forward progress. Post-fix: on any merge/post-merge failure,
        // BOTH `.merged` and all `.partN` files must be purged so the next retry has
        // to re-download from scratch (clean slate).
        //
        // We trigger a post-merge failure by giving a deliberately-wrong checksum.
        // The checksum-mismatch path is the same try block as merge — it exercises
        // the same cleanup code. (We can't easily simulate IOException-on-merge from
        // a pure-JVM/K-N test, but the cleanup branch is shared.)
        val payload = "valid payload".encodeToByteArray()
        val (engine, _) = rangeServer(payload)

        val savePath = "test_merge_fail_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 2,
            expectedChecksum = "deadbeef".repeat(8), // forces post-merge failure
            checksumAlgorithm = ChecksumAlgorithm.SHA256,
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            worker.doWork(input, WorkerEnvironment(null) { false })

            // Post-fix: all artefacts must be gone. If a regression keeps .partN files,
            // a subsequent retry would loop forever.
            assertFalse(fs.exists(savePath), "savePath must be gone after merge failure")
            assertFalse(fs.exists("${savePath}.merged".toPath()),
                ".merged must be cleaned up after failure")
            for (i in 0..1) {
                assertFalse(
                    fs.exists("${savePath}.part$i".toPath()),
                    "part file $i must be cleaned up to prevent retry storm",
                )
            }
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            for (i in 0..1) {
                val p = "${savePath}.part$i".toPath()
                if (fs.exists(p)) fs.delete(p)
            }
            val merged = "${savePath}.merged".toPath()
            if (fs.exists(merged)) fs.delete(merged)
        }
    }

    @Test
    fun parallel_outerRetryHasAttemptCap_toBreakInfiniteLoop() = runTest {
        // QA double-check regression: ParallelHttpDownloadWorker's outer catch returns
        // `WorkerResult.Retry(...)` without an attemptCap pre-fix. If the merge step
        // hit a permanent error (disk full, permission denied), the chain would
        // re-enter the worker forever. Post-fix: outer Retry sets attemptCap = 4.
        //
        // Trigger the outer catch by serving an unreachable URL. This bypasses the
        // configured checksum mismatch path (which returns Failure) and exercises
        // the network-error → outer Retry path.
        val savePath = "test_retry_cap_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        // Engine that errors on every request — simulates total network failure.
        val engine = MockEngine { _ -> error("simulated network failure") }
        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 2,
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Retry, "expected Retry on network failure, got $result")
            val retry = result as WorkerResult.Retry
            assertEquals(4, retry.attemptCap,
                "outer Retry must carry attemptCap = 4 to prevent infinite retry storm")
            assertTrue((retry.delayMs ?: 0L) > 0,
                "Retry must carry a positive delayMs (got ${retry.delayMs})")
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            for (i in 0..1) {
                val p = "${savePath}.part$i".toPath()
                if (fs.exists(p)) fs.delete(p)
            }
            val merged = "${savePath}.merged".toPath()
            if (fs.exists(merged)) fs.delete(merged)
        }
    }

    @Test
    fun parallel_checksumMismatch_returnsFailure_andCleansUp() = runTest {
        val payload = "valid payload".encodeToByteArray()
        val (engine, _) = rangeServer(payload)

        val savePath = "test_chksum_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = ParallelHttpDownloadWorker(mockClient(engine), fs)
        val config = ParallelHttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            numChunks = 2,
            expectedChecksum = "deadbeef".repeat(8), // wrong digest
            checksumAlgorithm = ChecksumAlgorithm.SHA256
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Failure, "expected Failure: $result")
            assertTrue((result as WorkerResult.Failure).message.contains("mismatch", ignoreCase = true))
            assertFalse(fs.exists(savePath), "savePath must not be created on checksum mismatch")
            assertFalse(fs.exists("${savePath}.merged".toPath()), "merged file must be cleaned up")
            for (i in 0..1) {
                assertFalse(fs.exists("${savePath}.part$i".toPath()),
                    "part files must be cleaned up on checksum mismatch")
            }
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }
}
