package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
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
        val worker = HttpDownloadWorker(HttpClient(mockEngine), fileSystem)
        
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
        val worker = HttpUploadWorker(HttpClient(mockEngine), fileSystem)
        
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
        val client = HttpClient(mockEngine)
        val worker = HttpRequestWorker(client)

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
        val client = HttpClient(mockEngine)
        val worker = HttpRequestWorker(client)

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
        val client = HttpClient(mockEngine)
        val worker = HttpRequestWorker(client)

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
        val worker = HttpSyncWorker(HttpClient(mockEngine))
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
        val worker = HttpUploadWorker(HttpClient(mockEngine), fileSystem)
        
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
}
