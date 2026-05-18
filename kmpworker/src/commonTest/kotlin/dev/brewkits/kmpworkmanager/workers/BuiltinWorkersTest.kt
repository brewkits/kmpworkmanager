package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlin.test.*
import kotlinx.coroutines.test.runTest

import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import okio.Path.Companion.toPath
import okio.FileSystem
import okio.buffer

class BuiltinWorkersTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
    }

    @Test
    fun testHttpDownloadWorkerSuccess() = runTest {
        val testFile = "test_download.txt".toPath()
        val mockEngine = MockEngine { request ->
            respond(
                content = "File content",
                status = HttpStatusCode.OK
            )
        }
        val fileSystem = FileSystem.SYSTEM
        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        
        val config = HttpDownloadConfig(
            url = "https://api.example.com/file",
            savePath = testFile.toString()
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null, { false }))

            assertTrue(result is WorkerResult.Success)
            assertTrue(fileSystem.exists(testFile))
            assertEquals("Downloaded 12 B", result.message)
        } finally {
            if (fileSystem.exists(testFile)) fileSystem.delete(testFile)
        }
    }

    @Test
    fun testHttpUploadWorkerSuccess() = runTest {
        val testFile = "test_upload.txt".toPath()
        val fileSystem = FileSystem.SYSTEM
        fileSystem.write(testFile) {
            writeUtf8("Upload this data")
        }

        val mockEngine = MockEngine { request ->
            respond(
                content = "Uploaded",
                status = HttpStatusCode.OK
            )
        }
        val worker = HttpUploadWorker(mockClient(mockEngine), fileSystem)
        
        val config = HttpUploadConfig(
            url = "https://api.example.com/upload",
            filePath = testFile.toString(),
            fileFieldName = "file"
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null, { false }))

            assertTrue(result is WorkerResult.Success)
            assertEquals("Uploaded", result.message)
        } finally {
            if (fileSystem.exists(testFile)) fileSystem.delete(testFile)
        }
    }

    @Test
    fun testHttpRequestWorkerSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val worker = HttpRequestWorker(mockClient(mockEngine))

        val config = HttpRequestConfig(
            url = "https://api.example.com/test",
            method = "GET"
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        val result = worker.doWork(input, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Success)
        assertEquals("HTTP 200 - GET https://api.example.com/test", result.message)
    }

    @Test
    fun testHttpRequestWorkerError404() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }
        val worker = HttpRequestWorker(mockClient(mockEngine))

        val config = HttpRequestConfig(
            url = "https://api.example.com/invalid",
            method = "GET"
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        val result = worker.doWork(input, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Failure)
        assertEquals("HTTP 404 error", result.message)
        assertFalse(result.shouldRetry)
    }

    @Test
    fun testHttpRequestWorkerRetryOn500() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = "Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val worker = HttpRequestWorker(mockClient(mockEngine))

        val config = HttpRequestConfig(
            url = "https://api.example.com/buggy",
            method = "POST",
            body = """{"data":"test"}"""
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        val result = worker.doWork(input, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Failure)
        assertEquals("HTTP 500 error", result.message)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun testHttpRequestWorkerInvalidURL() = runTest {
        val worker = HttpRequestWorker()
        
        // Metadata URLs are blocked by SecurityValidator
        val config = HttpRequestConfig(
            url = "http://169.254.169.254/latest/meta-data/",
            method = "GET"
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        val result = worker.doWork(input, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Failure)
        assertEquals("Invalid or unsafe URL", result.message)
    }

    @Test
    fun testHttpSyncWorkerSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """{"status":"synced"}""",
                status = HttpStatusCode.OK
            )
        }
        val worker = HttpSyncWorker(mockClient(mockEngine))
        val config = """{"url":"https://api.example.com/sync", "method":"GET"}"""

        val result = worker.doWork(config, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Success)
        assertEquals("Sync complete", result.message)
    }

    @Test
    fun testHttpUploadWorkerFailure() = runTest {
        val testFile = "test_upload_fail.txt".toPath()
        val fileSystem = FileSystem.SYSTEM
        fileSystem.write(testFile) {
            writeUtf8("Upload this data")
        }

        val mockEngine = MockEngine { request ->
            respond(
                content = "Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val worker = HttpUploadWorker(mockClient(mockEngine), fileSystem)

        val config = HttpUploadConfig(
            url = "https://api.example.com/upload",
            filePath = testFile.toString(),
            fileFieldName = "file"
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null, { false }))

            assertTrue(result is WorkerResult.Failure)
            assertEquals("HTTP 500", result.message)
        } finally {
            if (fileSystem.exists(testFile)) fileSystem.delete(testFile)
        }
    }

    // ── P1.3 Resumable download tests ───────────────────────────────────────────────

    @Test
    fun testHttpDownload_resumeFromPartialFile_appliesRangeHeader_andAppends() = runTest {
        val savePath = "test_resume_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fileSystem = FileSystem.SYSTEM

        // Pre-populate a partial with 5 bytes (simulating a previous interrupted attempt).
        fileSystem.write(partialPath) { writeUtf8("HELLO") }

        var observedRangeHeader: String? = null
        val mockEngine = MockEngine { request ->
            observedRangeHeader = request.headers[HttpHeaders.Range]
            respond(
                content = " WORLD",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 5-10/11")
            )
        }

        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        val config = HttpDownloadConfig(
            url = "https://example.com/file.bin",
            savePath = savePath.toString(),
            resumable = true
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })

            assertTrue(result is WorkerResult.Success, "expected Success, got $result")
            assertEquals("bytes=5-", observedRangeHeader, "worker must request Range from existing offset")
            assertTrue(fileSystem.exists(savePath), "final file must exist after finalize")
            assertFalse(fileSystem.exists(partialPath), "partial file must be moved/deleted after finalize")

            val contents = fileSystem.source(savePath).buffer().readUtf8()
            assertEquals("HELLO WORLD", contents, "partial + 206 body must concatenate")
        } finally {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
        }
    }

    @Test
    fun testHttpDownload_serverIgnoresRange_returns200_overwritesPartial() = runTest {
        val savePath = "test_resume_overwrite_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fileSystem = FileSystem.SYSTEM

        fileSystem.write(partialPath) { writeUtf8("STALE") }

        val mockEngine = MockEngine { request ->
            respond(content = "FRESH CONTENT", status = HttpStatusCode.OK)
        }

        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        val config = HttpDownloadConfig(
            url = "https://example.com/file.bin",
            savePath = savePath.toString(),
            resumable = true
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })

            assertTrue(result is WorkerResult.Success)
            val contents = fileSystem.source(savePath).buffer().readUtf8()
            assertEquals("FRESH CONTENT", contents, "200 OK must overwrite stale partial, not append")
        } finally {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
        }
    }

    @Test
    fun testHttpDownload_416_treatsPartialAsCompleted() = runTest {
        val savePath = "test_resume_416_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fileSystem = FileSystem.SYSTEM

        fileSystem.write(partialPath) { writeUtf8("COMPLETE") }

        val mockEngine = MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        val config = HttpDownloadConfig(
            url = "https://example.com/file.bin",
            savePath = savePath.toString(),
            resumable = true
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })

            assertTrue(result is WorkerResult.Success, "416 with a non-empty partial must succeed")
            val contents = fileSystem.source(savePath).buffer().readUtf8()
            assertEquals("COMPLETE", contents)
        } finally {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
        }
    }

    @Test
    fun testHttpDownload_5xx_returnsRetry_preservesPartial() = runTest {
        val savePath = "test_5xx_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fileSystem = FileSystem.SYSTEM

        fileSystem.write(partialPath) { writeUtf8("PARTIAL") }

        val mockEngine = MockEngine { _ ->
            respond(content = "boom", status = HttpStatusCode.BadGateway)
        }
        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        val config = HttpDownloadConfig(
            url = "https://example.com/file.bin",
            savePath = savePath.toString(),
            resumable = true
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })

            assertTrue(result is WorkerResult.Retry, "5xx must yield Retry, not Failure")
            assertTrue(fileSystem.exists(partialPath), "partial must be preserved across 5xx for next attempt")
        } finally {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
        }
    }

    @Test
    fun testHttpDownload_nonResumable_deletesPartialOnError() = runTest {
        val savePath = "test_nonresumable_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fileSystem = FileSystem.SYSTEM

        // Stale partial from a previous resumable run.
        fileSystem.write(partialPath) { writeUtf8("STALE") }

        var observedRangeHeader: String? = null
        val mockEngine = MockEngine { request ->
            observedRangeHeader = request.headers[HttpHeaders.Range]
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val worker = HttpDownloadWorker(mockClient(mockEngine), fileSystem)
        val config = HttpDownloadConfig(
            url = "https://example.com/file.bin",
            savePath = savePath.toString(),
            resumable = false
        )
        val input = KmpWorkManagerRuntime.json.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })

            assertTrue(result is WorkerResult.Success)
            assertNull(observedRangeHeader, "non-resumable mode must NOT send a Range header")
            val contents = fileSystem.source(savePath).buffer().readUtf8()
            assertEquals("ok", contents)
        } finally {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
        }
    }
}
