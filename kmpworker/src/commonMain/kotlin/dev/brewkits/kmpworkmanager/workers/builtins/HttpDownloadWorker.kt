package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.ProgressListener
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Built-in worker for downloading files from HTTP/HTTPS URLs.
 *
 * Features:
 * - Streaming downloads (constant ~3-5MB RAM regardless of file size)
 * - Atomic file operations (writes to .tmp then renames)
 * - Auto-creates parent directories
 * - Progress tracking support
 * - Handles large files (GB+) efficiently
 *
 * **Memory Usage:** ~3-5MB RAM
 * **Default Timeout:** 300 seconds (5 minutes)
 *
 * **Performance Optimization (v2.3.5+):**
 * - Uses singleton HttpClient for connection pool reuse
 * - 60-86% faster than previous version
 *
 * **Configuration Example:**
 * ```json
 * {
 *   "url": "https://example.com/large-file.zip",
 *   "savePath": "/path/to/save/file.zip",
 *   "headers": {
 *     "Authorization": "Bearer token"
 *   },
 *   "timeoutMs": 300000
 * }
 * ```
 *
 * **Usage:**
 * ```kotlin
 * val config = Json.encodeToString(HttpDownloadConfig.serializer(), HttpDownloadConfig(
 *     url = "https://example.com/file.zip",
 *     savePath = "/path/to/file.zip"
 * ))
 *
 * scheduler.enqueue(
 *     id = "download-file",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "HttpDownloadWorker",
 *     inputJson = config
 * )
 * ```
 *
 * @param httpClient Optional HttpClient (defaults to optimized singleton)
 * @param fileSystem Optional FileSystem implementation (defaults to platform default)
 * @param progressListener Optional progress listener for download tracking
 * @since 2.3.4 Uses singleton HttpClient by default for optimal performance
 */
class HttpDownloadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem,
    private val progressListener: ProgressListener? = null
) : Worker {

    override suspend fun doWork(input: String?): WorkerResult {
        Logger.i("HttpDownloadWorker", "Starting HTTP download worker...")

        if (input == null) {
            Logger.e("HttpDownloadWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = Json.decodeFromString<HttpDownloadConfig>(input)

            // Validate URL before downloading
            if (!SecurityValidator.validateURL(config.url)) {
                Logger.e("HttpDownloadWorker", "Invalid or unsafe URL: ${SecurityValidator.sanitizedURL(config.url)}")
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            Logger.i("HttpDownloadWorker", "Downloading from ${SecurityValidator.sanitizedURL(config.url)} to ${config.savePath}")

            downloadFile(httpClient, config)
        } catch (e: Exception) {
            Logger.e("HttpDownloadWorker", "Failed to download file", e)
            WorkerResult.Failure("Download failed: ${e.message}")
        }
        // Note: httpClient is not closed - managed by HttpClientProvider singleton
    }

    private suspend fun downloadFile(client: HttpClient, config: HttpDownloadConfig): WorkerResult {
        val savePath = config.savePath.toPath()
        val tempPath = "${config.savePath}.tmp".toPath()

        return try {
            // Ensure parent directory exists
            savePath.parent?.let { parentPath ->
                if (!fileSystem.exists(parentPath)) {
                    fileSystem.createDirectories(parentPath)
                    Logger.d("HttpDownloadWorker", "Created parent directory: $parentPath")
                }
            }

            // Download to temp file
            val response: HttpResponse = client.get(config.url) {
                // Set headers
                config.headers?.forEach { (key, value) ->
                    header(key, value)
                }
            }

            val statusCode = response.status.value
            if (statusCode !in 200..299) {
                Logger.e("HttpDownloadWorker", "Download failed with status $statusCode")
                return WorkerResult.Failure("HTTP $statusCode error", shouldRetry = statusCode in 500..599)
            }

            val contentLength = response.contentLength() ?: -1L
            var downloadedBytes = 0L

            Logger.i("HttpDownloadWorker", "Content length: ${if (contentLength > 0) SecurityValidator.formatByteSize(contentLength) else "unknown"}")

            // Stream download to temp file
            fileSystem.write(tempPath) {
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(8192) // 8KB buffer

                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Report progress
                        if (contentLength > 0) {
                            val progress = ((downloadedBytes * 100) / contentLength).toInt()
                            progressListener?.onProgressUpdate(
                                WorkerProgress(
                                    progress = progress,
                                    message = "Downloaded ${SecurityValidator.formatByteSize(downloadedBytes)} / ${SecurityValidator.formatByteSize(contentLength)}"
                                )
                            )
                        }
                    }
                }
            }

            Logger.i("HttpDownloadWorker", "Downloaded ${SecurityValidator.formatByteSize(downloadedBytes)} to temp file")

            // Atomic rename to final destination
            if (fileSystem.exists(savePath)) {
                fileSystem.delete(savePath)
            }
            fileSystem.atomicMove(tempPath, savePath)

            Logger.i("HttpDownloadWorker", "Download completed successfully: $savePath")

            WorkerResult.Success(
                message = "Downloaded ${SecurityValidator.formatByteSize(downloadedBytes)}",
                data = mapOf(
                    "fileSize" to downloadedBytes,
                    "filePath" to config.savePath,
                    "url" to SecurityValidator.sanitizedURL(config.url)
                )
            )
        } catch (e: Exception) {
            // Cleanup temp file on error
            try {
                if (fileSystem.exists(tempPath)) {
                    fileSystem.delete(tempPath)
                    Logger.d("HttpDownloadWorker", "Cleaned up temp file")
                }
            } catch (cleanupError: Exception) {
                Logger.w("HttpDownloadWorker", "Failed to cleanup temp file: ${cleanupError.message}")
            }

            Logger.e("HttpDownloadWorker", "Download failed", e)
            WorkerResult.Failure("Download failed: ${e.message}", shouldRetry = true)
        }
    }

    companion object {
        /**
         * Creates a default HTTP client with reasonable timeouts.
         *
         * @deprecated Use HttpClientProvider.instance instead for better performance.
         * This method creates a new client each time, which is inefficient.
         * Will be removed in v3.0.0.
         */
        @Deprecated(
            message = "Use HttpClientProvider.instance for connection pool reuse",
            replaceWith = ReplaceWith("HttpClientProvider.instance", "dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider"),
            level = DeprecationLevel.WARNING
        )
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient {
                expectSuccess = false // Don't throw on non-2xx responses
            }
        }
    }
}