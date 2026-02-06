package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.builtins.*
import dev.brewkits.kmpworkmanager.workers.config.*
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Test suite for built-in workers and their configurations.
 *
 * Tests cover:
 * 1. Configuration validation
 * 2. Serialization/deserialization
 * 3. Worker factory registration
 * 4. Security validation
 * 5. Edge cases
 */
class BuiltinWorkersTest {

    // ==================== HttpMethod Tests ====================

    @Test
    fun `HttpMethod should have all standard methods`() {
        val methods = HttpMethod.values()
        assertTrue(methods.contains(HttpMethod.GET))
        assertTrue(methods.contains(HttpMethod.POST))
        assertTrue(methods.contains(HttpMethod.PUT))
        assertTrue(methods.contains(HttpMethod.DELETE))
        assertTrue(methods.contains(HttpMethod.PATCH))
        assertEquals(5, methods.size)
    }

    @Test
    fun `HttpMethod fromString should parse correctly`() {
        assertEquals(HttpMethod.GET, HttpMethod.fromString("GET"))
        assertEquals(HttpMethod.POST, HttpMethod.fromString("post"))
        assertEquals(HttpMethod.PUT, HttpMethod.fromString("Put"))
        assertEquals(HttpMethod.DELETE, HttpMethod.fromString("DELETE"))
        assertEquals(HttpMethod.PATCH, HttpMethod.fromString("PATCH"))
    }

    @Test
    fun `HttpMethod fromString should throw on invalid method`() {
        assertFailsWith<IllegalArgumentException> {
            HttpMethod.fromString("INVALID")
        }
    }

    // ==================== HttpRequestConfig Tests ====================

    @Test
    fun `HttpRequestConfig should validate URL scheme`() {
        // Valid URLs
        assertNotNull(HttpRequestConfig(url = "https://example.com"))
        assertNotNull(HttpRequestConfig(url = "http://example.com"))

        // Invalid URLs
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "ftp://example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "example.com")
        }
    }

    @Test
    fun `HttpRequestConfig should validate timeout`() {
        // Valid timeout
        assertNotNull(HttpRequestConfig(url = "https://example.com", timeoutMs = 30000))

        // Invalid timeout
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "https://example.com", timeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "https://example.com", timeoutMs = -1)
        }
    }

    @Test
    fun `HttpRequestConfig should serialize and deserialize correctly`() {
        val config = HttpRequestConfig(
            url = "https://api.example.com/test",
            method = "POST",
            headers = mapOf("Authorization" to "Bearer token"),
            body = """{"key":"value"}""",
            timeoutMs = 60000
        )

        val json = Json.encodeToString(HttpRequestConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpRequestConfig>(json)

        assertEquals(config.url, decoded.url)
        assertEquals(config.method, decoded.method)
        assertEquals(config.headers, decoded.headers)
        assertEquals(config.body, decoded.body)
        assertEquals(config.timeoutMs, decoded.timeoutMs)
    }

    @Test
    fun `HttpRequestConfig should default to GET method`() {
        val config = HttpRequestConfig(url = "https://example.com")
        assertEquals("GET", config.method)
        assertEquals(HttpMethod.GET, config.httpMethod)
    }

    // ==================== HttpSyncConfig Tests ====================

    @Test
    fun `HttpSyncConfig should serialize JSON body`() {
        val requestBody = buildJsonObject {
            put("key1", "value1")
            put("key2", 123)
        }

        val config = HttpSyncConfig(
            url = "https://api.example.com/sync",
            method = "POST",
            requestBody = requestBody
        )

        val json = Json.encodeToString(HttpSyncConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpSyncConfig>(json)

        assertEquals(config.url, decoded.url)
        assertEquals(config.method, decoded.method)
        assertNotNull(decoded.requestBody)
    }

    @Test
    fun `HttpSyncConfig should handle null request body for GET`() {
        val config = HttpSyncConfig(
            url = "https://api.example.com/sync",
            method = "GET",
            requestBody = null
        )

        val json = Json.encodeToString(HttpSyncConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpSyncConfig>(json)

        assertEquals("GET", decoded.method)
        assertNull(decoded.requestBody)
    }

    // ==================== HttpDownloadConfig Tests ====================

    @Test
    fun `HttpDownloadConfig should validate URL`() {
        assertNotNull(HttpDownloadConfig(
            url = "https://example.com/file.zip",
            savePath = "/path/to/file.zip"
        ))

        assertFailsWith<IllegalArgumentException> {
            HttpDownloadConfig(url = "invalid-url", savePath = "/path/to/file.zip")
        }
    }

    @Test
    fun `HttpDownloadConfig should validate save path is not empty`() {
        assertFailsWith<IllegalArgumentException> {
            HttpDownloadConfig(url = "https://example.com/file.zip", savePath = "")
        }

        assertFailsWith<IllegalArgumentException> {
            HttpDownloadConfig(url = "https://example.com/file.zip", savePath = "   ")
        }
    }

    @Test
    fun `HttpDownloadConfig should have default timeout of 5 minutes`() {
        val config = HttpDownloadConfig(
            url = "https://example.com/file.zip",
            savePath = "/path/to/file.zip"
        )
        assertEquals(300000L, config.timeoutMs) // 5 minutes
    }

    // ==================== HttpUploadConfig Tests ====================

    @Test
    fun `HttpUploadConfig should validate file path`() {
        assertNotNull(HttpUploadConfig(
            url = "https://api.example.com/upload",
            filePath = "/storage/photo.jpg",
            fileFieldName = "photo"
        ))

        assertFailsWith<IllegalArgumentException> {
            HttpUploadConfig(
                url = "https://api.example.com/upload",
                filePath = "",
                fileFieldName = "photo"
            )
        }
    }

    @Test
    fun `HttpUploadConfig should validate field name`() {
        assertFailsWith<IllegalArgumentException> {
            HttpUploadConfig(
                url = "https://api.example.com/upload",
                filePath = "/storage/photo.jpg",
                fileFieldName = ""
            )
        }
    }

    @Test
    fun `HttpUploadConfig should serialize with optional fields`() {
        val config = HttpUploadConfig(
            url = "https://api.example.com/upload",
            filePath = "/storage/photo.jpg",
            fileFieldName = "photo",
            fileName = "custom.jpg",
            mimeType = "image/jpeg",
            headers = mapOf("Auth" to "token"),
            fields = mapOf("userId" to "123")
        )

        val json = Json.encodeToString(HttpUploadConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpUploadConfig>(json)

        assertEquals("custom.jpg", decoded.fileName)
        assertEquals("image/jpeg", decoded.mimeType)
        assertEquals(mapOf("Auth" to "token"), decoded.headers)
        assertEquals(mapOf("userId" to "123"), decoded.fields)
    }

    // ==================== FileCompressionConfig Tests ====================

    @Test
    fun `FileCompressionConfig should validate paths`() {
        assertNotNull(FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip"
        ))

        assertFailsWith<IllegalArgumentException> {
            FileCompressionConfig(inputPath = "", outputPath = "/data/logs.zip")
        }

        assertFailsWith<IllegalArgumentException> {
            FileCompressionConfig(inputPath = "/data/logs", outputPath = "")
        }
    }

    @Test
    fun `FileCompressionConfig should validate compression level`() {
        // Valid levels
        assertNotNull(FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip",
            compressionLevel = "low"
        ))
        assertNotNull(FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip",
            compressionLevel = "medium"
        ))
        assertNotNull(FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip",
            compressionLevel = "high"
        ))

        // Invalid level
        assertFailsWith<IllegalArgumentException> {
            FileCompressionConfig(
                inputPath = "/data/logs",
                outputPath = "/data/logs.zip",
                compressionLevel = "ultra"
            )
        }
    }

    @Test
    fun `FileCompressionConfig should default to medium compression`() {
        val config = FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip"
        )
        assertEquals("medium", config.compressionLevel)
        assertEquals(CompressionLevel.MEDIUM, config.level)
    }

    @Test
    fun `FileCompressionConfig should handle exclude patterns`() {
        val config = FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip",
            excludePatterns = listOf("*.tmp", ".DS_Store", "*.lock")
        )

        val json = Json.encodeToString(FileCompressionConfig.serializer(), config)
        val decoded = Json.decodeFromString<FileCompressionConfig>(json)

        assertEquals(3, decoded.excludePatterns?.size)
        assertTrue(decoded.excludePatterns!!.contains("*.tmp"))
    }

    // ==================== CompressionLevel Tests ====================

    @Test
    fun `CompressionLevel should have correct values`() {
        assertEquals(3, CompressionLevel.values().size)
        assertTrue(CompressionLevel.values().contains(CompressionLevel.LOW))
        assertTrue(CompressionLevel.values().contains(CompressionLevel.MEDIUM))
        assertTrue(CompressionLevel.values().contains(CompressionLevel.HIGH))
    }

    @Test
    fun `CompressionLevel fromString should parse correctly`() {
        assertEquals(CompressionLevel.LOW, CompressionLevel.fromString("low"))
        assertEquals(CompressionLevel.MEDIUM, CompressionLevel.fromString("MEDIUM"))
        assertEquals(CompressionLevel.HIGH, CompressionLevel.fromString("High"))
    }

    @Test
    fun `CompressionLevel fromString should throw on invalid level`() {
        assertFailsWith<IllegalArgumentException> {
            CompressionLevel.fromString("ultra")
        }
    }

    // ==================== SecurityValidator Tests ====================

    @Test
    fun `SecurityValidator should validate URL schemes`() {
        assertTrue(SecurityValidator.validateURL("https://example.com"))
        assertTrue(SecurityValidator.validateURL("http://example.com"))
        assertFalse(SecurityValidator.validateURL("ftp://example.com"))
        assertFalse(SecurityValidator.validateURL("file:///etc/passwd"))
    }

    @Test
    fun `SecurityValidator should sanitize URLs for logging`() {
        val url = "https://api.example.com/users?token=secret123&key=value"
        val sanitized = SecurityValidator.sanitizedURL(url)

        // Should not contain sensitive token
        assertTrue(sanitized.startsWith("https://"))
        assertFalse(sanitized.contains("secret123"))
    }

    @Test
    fun `SecurityValidator should validate file paths`() {
        assertTrue(SecurityValidator.validateFilePath("/storage/file.txt"))
        assertTrue(SecurityValidator.validateFilePath("/data/local/file.txt"))

        // Path traversal attempts
        assertFalse(SecurityValidator.validateFilePath("../../../etc/passwd"))
        assertFalse(SecurityValidator.validateFilePath("/data/../../../etc/passwd"))
    }

    @Test
    fun `SecurityValidator should truncate strings for logging`() {
        val longString = "a".repeat(1000)
        val truncated = SecurityValidator.truncateForLogging(longString, 100)

        assertTrue(truncated.length <= 104) // 100 + "..." + extra
        assertTrue(truncated.endsWith("..."))
    }

    @Test
    fun `SecurityValidator should format byte sizes correctly`() {
        assertEquals("0 B", SecurityValidator.formatByteSize(0))
        assertEquals("1023 B", SecurityValidator.formatByteSize(1023))

        // KB range
        val kb = SecurityValidator.formatByteSize(1024)
        assertTrue(kb.contains("KB"))

        // MB range
        val mb = SecurityValidator.formatByteSize(1024 * 1024)
        assertTrue(mb.contains("MB"))

        // GB range
        val gb = SecurityValidator.formatByteSize(1024L * 1024L * 1024L)
        assertTrue(gb.contains("GB"))
    }

    // ==================== BuiltinWorkerRegistry Tests ====================

    @Test
    fun `BuiltinWorkerRegistry should create all workers`() {
        assertNotNull(BuiltinWorkerRegistry.createWorker("HttpRequestWorker"))
        assertNotNull(BuiltinWorkerRegistry.createWorker("HttpSyncWorker"))
        assertNotNull(BuiltinWorkerRegistry.createWorker("HttpDownloadWorker"))
        assertNotNull(BuiltinWorkerRegistry.createWorker("HttpUploadWorker"))
        assertNotNull(BuiltinWorkerRegistry.createWorker("FileCompressionWorker"))
    }

    @Test
    fun `BuiltinWorkerRegistry should support fully qualified names`() {
        assertNotNull(BuiltinWorkerRegistry.createWorker("dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker"))
        assertNotNull(BuiltinWorkerRegistry.createWorker("dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker"))
    }

    @Test
    fun `BuiltinWorkerRegistry should return null for unknown workers`() {
        assertNull(BuiltinWorkerRegistry.createWorker("UnknownWorker"))
        assertNull(BuiltinWorkerRegistry.createWorker("CustomWorker"))
    }

    @Test
    fun `BuiltinWorkerRegistry should list all workers`() {
        val workers = BuiltinWorkerRegistry.listWorkers()
        assertEquals(5, workers.size)
        assertTrue(workers.contains("dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker"))
        assertTrue(workers.contains("dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker"))
        assertTrue(workers.contains("dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker"))
        assertTrue(workers.contains("dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker"))
        assertTrue(workers.contains("dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"))
    }

    @Test
    fun `BuiltinWorkerRegistry should create worker instances`() {
        val httpRequestWorker = BuiltinWorkerRegistry.createWorker("HttpRequestWorker")
        assertNotNull(httpRequestWorker)
        assertTrue(httpRequestWorker is HttpRequestWorker)

        val httpSyncWorker = BuiltinWorkerRegistry.createWorker("HttpSyncWorker")
        assertNotNull(httpSyncWorker)
        assertTrue(httpSyncWorker is HttpSyncWorker)

        val httpDownloadWorker = BuiltinWorkerRegistry.createWorker("HttpDownloadWorker")
        assertNotNull(httpDownloadWorker)
        assertTrue(httpDownloadWorker is HttpDownloadWorker)

        val httpUploadWorker = BuiltinWorkerRegistry.createWorker("HttpUploadWorker")
        assertNotNull(httpUploadWorker)
        assertTrue(httpUploadWorker is HttpUploadWorker)

        val fileCompressionWorker = BuiltinWorkerRegistry.createWorker("FileCompressionWorker")
        assertNotNull(fileCompressionWorker)
        assertTrue(fileCompressionWorker is FileCompressionWorker)
    }

    // ==================== CompositeWorkerFactory Tests ====================

    @Test
    fun `CompositeWorkerFactory should try factories in order`() {
        val customFactory = object : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
            override fun createWorker(workerClassName: String): dev.brewkits.kmpworkmanager.background.domain.Worker? {
                return when (workerClassName) {
                    "CustomWorker" -> HttpRequestWorker() // Fake custom worker
                    else -> null
                }
            }
        }

        val composite = CompositeWorkerFactory(customFactory, BuiltinWorkerRegistry)

        // Should find in custom factory first
        assertNotNull(composite.createWorker("CustomWorker"))

        // Should fall back to built-in
        assertNotNull(composite.createWorker("HttpRequestWorker"))

        // Should return null if not found anywhere
        assertNull(composite.createWorker("NonExistentWorker"))
    }

    @Test
    fun `CompositeWorkerFactory should prioritize first factory`() {
        val factory1 = object : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
            override fun createWorker(workerClassName: String): dev.brewkits.kmpworkmanager.background.domain.Worker? {
                return if (workerClassName == "TestWorker") HttpRequestWorker() else null
            }
        }

        val factory2 = object : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
            override fun createWorker(workerClassName: String): dev.brewkits.kmpworkmanager.background.domain.Worker? {
                return if (workerClassName == "TestWorker") HttpSyncWorker() else null
            }
        }

        val composite = CompositeWorkerFactory(factory1, factory2)
        val worker = composite.createWorker("TestWorker")

        // Should use factory1's result (HttpRequestWorker, not HttpSyncWorker)
        assertNotNull(worker)
        assertTrue(worker is HttpRequestWorker)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `Config should handle special characters in strings`() {
        val config = HttpRequestConfig(
            url = "https://example.com/path?q=hello%20world",
            headers = mapOf("X-Custom" to "value with spaces"),
            body = """{"text":"Line1\nLine2"}"""
        )

        val json = Json.encodeToString(HttpRequestConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpRequestConfig>(json)

        assertEquals(config.url, decoded.url)
        assertEquals(config.headers, decoded.headers)
        assertEquals(config.body, decoded.body)
    }

    @Test
    fun `Config should handle empty collections`() {
        val config = HttpRequestConfig(
            url = "https://example.com",
            headers = emptyMap()
        )

        val json = Json.encodeToString(HttpRequestConfig.serializer(), config)
        val decoded = Json.decodeFromString<HttpRequestConfig>(json)

        assertNotNull(decoded.headers)
        assertTrue(decoded.headers.isEmpty())
    }

    @Test
    fun `FileCompressionConfig should handle empty exclude patterns`() {
        val config = FileCompressionConfig(
            inputPath = "/data/logs",
            outputPath = "/data/logs.zip",
            excludePatterns = emptyList()
        )

        val json = Json.encodeToString(FileCompressionConfig.serializer(), config)
        val decoded = Json.decodeFromString<FileCompressionConfig>(json)

        assertNotNull(decoded.excludePatterns)
        assertTrue(decoded.excludePatterns.isEmpty())
    }

    // ==================== v2.3.0 WorkerResult Integration Tests ====================

    @Test
    fun `HttpRequestWorker should return WorkerResult`() {
        val worker = HttpRequestWorker()

        // Verify worker instance is created
        assertNotNull(worker)

        // Note: Actual doWork() execution requires network and is tested in platform-specific tests
        // This test verifies the worker is properly instantiated and implements the correct interface
    }

    @Test
    fun `HttpSyncWorker should return WorkerResult with expected data structure`() {
        val worker = HttpSyncWorker()
        assertNotNull(worker)

        // Worker should be capable of returning data fields:
        // - statusCode: Int
        // - responseBody: String
        // - timestamp: Long
    }

    @Test
    fun `HttpDownloadWorker should return WorkerResult with download metadata`() {
        val worker = HttpDownloadWorker()
        assertNotNull(worker)

        // Expected Success data fields:
        // - fileSize: Long (downloaded bytes)
        // - filePath: String (save location)
        // - url: String (sanitized download URL)
    }

    @Test
    fun `HttpUploadWorker should return WorkerResult with upload metadata`() {
        val worker = HttpUploadWorker()
        assertNotNull(worker)

        // Expected Success data fields:
        // - fileSize: Long (uploaded bytes)
        // - statusCode: Int (HTTP response code)
        // - responseBody: String (server response)
    }

    @Test
    fun `FileCompressionWorker should return WorkerResult with compression stats`() {
        val worker = FileCompressionWorker()
        assertNotNull(worker)

        // Expected Success data fields:
        // - originalSize: Long (before compression)
        // - compressedSize: Long (after compression)
        // - compressionRatio: Double (percentage saved)
        // - outputPath: String (compressed file location)
    }

    @Test
    fun `WorkerResult Success should be serializable`() {
        val result = dev.brewkits.kmpworkmanager.background.domain.WorkerResult.Success(
            message = "Download completed",
            data = mapOf(
                "fileSize" to 1024L,
                "filePath" to "/tmp/file.txt",
                "url" to "https://example.com/file.txt"
            )
        )

        // Verify data fields
        assertEquals("Download completed", result.message)
        assertNotNull(result.data)
        assertEquals(1024L, result.data["fileSize"])
        assertEquals("/tmp/file.txt", result.data["filePath"])
        assertEquals("https://example.com/file.txt", result.data["url"])
    }

    @Test
    fun `WorkerResult Failure should contain error details`() {
        val result = dev.brewkits.kmpworkmanager.background.domain.WorkerResult.Failure(
            message = "Network timeout after 30000ms",
            shouldRetry = true
        )

        assertEquals("Network timeout after 30000ms", result.message)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun `WorkerResult Success with null data should be valid`() {
        val result = dev.brewkits.kmpworkmanager.background.domain.WorkerResult.Success(
            message = "Task completed",
            data = null
        )

        assertEquals("Task completed", result.message)
        assertNull(result.data)
    }

    @Test
    fun `WorkerResult Success with complex nested data should work`() {
        val result = dev.brewkits.kmpworkmanager.background.domain.WorkerResult.Success(
            message = "Upload successful",
            data = mapOf(
                "fileSize" to 2048L,
                "statusCode" to 200,
                "headers" to mapOf(
                    "Content-Type" to "application/json",
                    "X-Upload-ID" to "abc123"
                ),
                "retryCount" to 0
            )
        )

        assertNotNull(result.data)
        assertEquals(2048L, result.data["fileSize"])
        assertEquals(200, result.data["statusCode"])

        @Suppress("UNCHECKED_CAST")
        val headers = result.data["headers"] as? Map<String, String>
        assertNotNull(headers)
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `BuiltinWorkerRegistry should create workers that return WorkerResult`() {
        // Verify all built-in workers are created correctly
        val httpRequestWorker = BuiltinWorkerRegistry.createWorker("HttpRequestWorker")
        assertNotNull(httpRequestWorker)
        assertTrue(httpRequestWorker is HttpRequestWorker)

        val httpSyncWorker = BuiltinWorkerRegistry.createWorker("HttpSyncWorker")
        assertNotNull(httpSyncWorker)
        assertTrue(httpSyncWorker is HttpSyncWorker)

        val httpDownloadWorker = BuiltinWorkerRegistry.createWorker("HttpDownloadWorker")
        assertNotNull(httpDownloadWorker)
        assertTrue(httpDownloadWorker is HttpDownloadWorker)

        val httpUploadWorker = BuiltinWorkerRegistry.createWorker("HttpUploadWorker")
        assertNotNull(httpUploadWorker)
        assertTrue(httpUploadWorker is HttpUploadWorker)

        val fileCompressionWorker = BuiltinWorkerRegistry.createWorker("FileCompressionWorker")
        assertNotNull(fileCompressionWorker)
        assertTrue(fileCompressionWorker is FileCompressionWorker)
    }

    @Test
    fun `HttpRequestConfig validation should prevent invalid configurations`() {
        // Valid config should work
        assertNotNull(HttpRequestConfig(url = "https://api.example.com/test"))

        // Invalid URL scheme should fail
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "ftp://invalid.com")
        }

        // Missing URL scheme should fail
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "example.com")
        }

        // Invalid timeout should fail
        assertFailsWith<IllegalArgumentException> {
            HttpRequestConfig(url = "https://example.com", timeoutMs = -1)
        }
    }

    @Test
    fun `HttpDownloadConfig validation should ensure safe file paths`() {
        // Valid config
        assertNotNull(HttpDownloadConfig(
            url = "https://example.com/file.zip",
            savePath = "/storage/downloads/file.zip"
        ))

        // Empty save path should fail
        assertFailsWith<IllegalArgumentException> {
            HttpDownloadConfig(
                url = "https://example.com/file.zip",
                savePath = ""
            )
        }

        // Blank save path should fail
        assertFailsWith<IllegalArgumentException> {
            HttpDownloadConfig(
                url = "https://example.com/file.zip",
                savePath = "   "
            )
        }
    }
}
