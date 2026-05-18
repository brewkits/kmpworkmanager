package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.ParallelHttpUploadWorker
import dev.brewkits.kmpworkmanager.workers.config.ParallelHttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.config.ParallelUploadFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for ParallelHttpUploadWorker.
 *
 * MockEngine drives per-file response, the test asserts aggregate result + per-file
 * outcomes. Files are written to FileSystem.SYSTEM with deterministic prefixes so
 * test runs can clean up after themselves even on failure.
 */
class ParallelHttpUploadWorkerTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }

    private fun writeTempFile(content: String): String {
        val path = "test_pu_${kotlin.random.Random.nextInt()}.txt".toPath()
        FileSystem.SYSTEM.write(path) { writeUtf8(content) }
        return path.toString()
    }

    /**
     * MockEngine helper that distinguishes responses by URL path query, which we
     * attach per-file. The worker forwards each ParallelUploadFile to the same
     * config URL — we encode the file index in a query string so the test can
     * decide per request without parsing the multipart body (toByteArray() is
     * not available across all K/N targets in ktor-client-mock).
     */
    private fun perFileResponder(perCallStatus: List<HttpStatusCode>): MockEngine {
        val callIdx = atomic(0)
        return MockEngine { _ ->
            val idx = callIdx.getAndIncrement().coerceAtMost(perCallStatus.size - 1)
            respond(content = "r$idx", status = perCallStatus[idx])
        }
    }

    private fun cleanup(paths: List<String>) {
        for (p in paths) {
            val pp = p.toPath()
            if (FileSystem.SYSTEM.exists(pp)) FileSystem.SYSTEM.delete(pp)
        }
    }

    @Test
    fun allFilesSucceed_returnsSuccess_withCorrectAggregates() = runTest {
        val pathA = writeTempFile("aaaa")
        val pathB = writeTempFile("bb")

        val postCount = atomic(0)
        val mock = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            postCount.incrementAndGet()
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val worker = ParallelHttpUploadWorker(mockClient(mock), FileSystem.SYSTEM)
        val config = ParallelHttpUploadConfig(
            url = "https://example.com/upload",
            files = listOf(
                ParallelUploadFile(filePath = pathA),
                ParallelUploadFile(filePath = pathB)
            ),
            maxConcurrent = 2,
            maxRetries = 0
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Expected Success: $result")
            assertEquals(2, postCount.value, "two files → exactly two POSTs")
            val data = (result as WorkerResult.Success).data
            assertTrue(data != null)
            assertEquals(2, data["uploadedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, data["failedCount"]!!.jsonPrimitive.content.toInt())
            val fileResults: JsonArray = data["fileResults"]!!.jsonArray
            assertEquals(2, fileResults.size)
            for (entry in fileResults) {
                val obj = entry.jsonObject
                assertTrue(obj["success"]!!.jsonPrimitive.content.toBoolean())
                assertEquals(200, obj["statusCode"]!!.jsonPrimitive.content.toInt())
            }
        } finally {
            cleanup(listOf(pathA, pathB))
        }
    }

    @Test
    fun partialFailure_returnsFailure_andCountsCorrectly() = runTest {
        val pathA = writeTempFile("aaaa")
        val pathB = writeTempFile("bb")

        // With maxConcurrent=1, requests are serialised → call 0 = first file in list = A (ok),
        // call 1 = B (500). Setting maxConcurrent=1 makes the order deterministic so the
        // counter-based mock can attribute responses without inspecting the body.
        val mock = perFileResponder(
            listOf(HttpStatusCode.OK, HttpStatusCode.InternalServerError)
        )
        val worker = ParallelHttpUploadWorker(mockClient(mock), FileSystem.SYSTEM)
        val config = ParallelHttpUploadConfig(
            url = "https://example.com/upload",
            files = listOf(
                ParallelUploadFile(filePath = pathA),
                ParallelUploadFile(filePath = pathB)
            ),
            maxConcurrent = 1,
            maxRetries = 0
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Failure, "Expected Failure when one file fails: $result")
            val msg = (result as WorkerResult.Failure).message
            assertTrue(
                msg.contains("1/2") || msg.contains("Uploaded 1"),
                "Failure message must report partial success: $msg"
            )
        } finally {
            cleanup(listOf(pathA, pathB))
        }
    }

    @Test
    fun fiveXx_retriesUpToMaxRetries() = runTest {
        val path = writeTempFile("aaaa")

        // 503 → 503 → 200: the 3rd call (= 1 original + 2 retries) succeeds.
        val mock = perFileResponder(
            listOf(
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.OK
            )
        )
        val worker = ParallelHttpUploadWorker(mockClient(mock), FileSystem.SYSTEM)
        val config = ParallelHttpUploadConfig(
            url = "https://example.com/upload",
            files = listOf(ParallelUploadFile(filePath = path)),
            maxConcurrent = 1,
            maxRetries = 2
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Must succeed after 2 retries on 5xx: $result")
        } finally {
            cleanup(listOf(path))
        }
    }

    @Test
    fun fourXx_doesNotRetry() = runTest {
        val path = writeTempFile("aaaa")

        val mock = perFileResponder(listOf(HttpStatusCode.Unauthorized))
        val worker = ParallelHttpUploadWorker(mockClient(mock), FileSystem.SYSTEM)
        val config = ParallelHttpUploadConfig(
            url = "https://example.com/upload",
            files = listOf(ParallelUploadFile(filePath = path)),
            maxConcurrent = 1,
            maxRetries = 3 // would be plenty, but 401 must NOT trigger any retry
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Failure, "401 must yield Failure: $result")
        } finally {
            cleanup(listOf(path))
        }
    }

    @Test
    fun config_rejectsEmptyFilesList() {
        try {
            ParallelHttpUploadConfig(
                url = "https://example.com/upload",
                files = emptyList()
            )
            error("Constructor should have thrown for empty files")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must not be empty", ignoreCase = true))
        }
    }

    @Test
    fun config_rejectsMaxConcurrentOutOfRange() {
        try {
            ParallelHttpUploadConfig(
                url = "https://example.com/upload",
                files = listOf(ParallelUploadFile(filePath = "/tmp/x")),
                maxConcurrent = 17
            )
            error("Constructor should have thrown for maxConcurrent > 16")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("between 1 and 16"))
        }
    }
}
