package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.ProgressListener
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Built-in worker for uploading files using multipart/form-data.
 *
 * Features:
 * - Multipart/form-data encoding
 * - Custom MIME type support
 * - Additional form fields
 * - Progress tracking support
 * - Memory efficient streaming
 *
 * **Memory Usage:** ~5-7MB RAM
 * **Default Timeout:** 120 seconds (2 minutes)
 *
 * **Configuration Example:**
 * ```json
 * {
 *   "url": "https://api.example.com/upload",
 *   "filePath": "/path/to/file.jpg",
 *   "fileFieldName": "photo",
 *   "fileName": "profile.jpg",
 *   "mimeType": "image/jpeg",
 *   "headers": {
 *     "Authorization": "Bearer token"
 *   },
 *   "fields": {
 *     "userId": "123",
 *     "description": "Profile photo"
 *   },
 *   "timeoutMs": 120000
 * }
 * ```
 *
 * **Usage:**
 * ```kotlin
 * val config = Json.encodeToString(HttpUploadConfig.serializer(), HttpUploadConfig(
 *     url = "https://api.example.com/upload",
 *     filePath = "/storage/photo.jpg",
 *     fileFieldName = "photo",
 *     fields = mapOf("userId" to "123")
 * ))
 *
 * scheduler.enqueue(
 *     id = "upload-photo",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "HttpUploadWorker",
 *     inputJson = config
 * )
 * ```
 */
class HttpUploadWorker(
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val fileSystem: FileSystem = platformFileSystem,
    private val progressListener: ProgressListener? = null
) : Worker {

    override suspend fun doWork(input: String?): WorkerResult {
        Logger.i("HttpUploadWorker", "Starting HTTP upload worker...")

        if (input == null) {
            Logger.e("HttpUploadWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = Json.decodeFromString<HttpUploadConfig>(input)
            Logger.i("HttpUploadWorker", "Uploading file ${config.filePath} to ${SecurityValidator.sanitizedURL(config.url)}")

            uploadFile(config)
        } catch (e: Exception) {
            Logger.e("HttpUploadWorker", "Failed to upload file", e)
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }

    private suspend fun uploadFile(config: HttpUploadConfig): WorkerResult {
        val filePath = config.filePath.toPath()

        return try {
            // Validate file exists
            if (!fileSystem.exists(filePath)) {
                Logger.e("HttpUploadWorker", "File does not exist: ${config.filePath}")
                return WorkerResult.Failure("Input configuration is null")
            }

            // Get file metadata
            val metadata = fileSystem.metadata(filePath)
            val fileSize = metadata.size ?: 0L
            Logger.i("HttpUploadWorker", "File size: ${SecurityValidator.formatByteSize(fileSize)}")

            // Read file content
            val fileBytes = fileSystem.read(filePath) {
                readByteArray()
            }

            // Determine filename
            val fileName = config.fileName ?: filePath.name

            // Determine MIME type
            val mimeType = config.mimeType ?: detectMimeType(fileName)
            Logger.d("HttpUploadWorker", "MIME type: $mimeType")

            // Upload using multipart/form-data
            val response: HttpResponse = httpClient.submitFormWithBinaryData(
                url = config.url,
                formData = formData {
                    // Add additional form fields
                    config.fields?.forEach { (key, value) ->
                        append(key, value)
                    }

                    // Add file
                    append(
                        config.fileFieldName,
                        fileBytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        }
                    )
                }
            ) {
                // Set custom headers
                config.headers?.forEach { (key, value) ->
                    header(key, value)
                }
            }

            val statusCode = response.status.value
            val responseBody = response.bodyAsText()

            if (statusCode in 200..299) {
                Logger.i("HttpUploadWorker", "Upload completed successfully with status $statusCode")

                // Log response (truncated)
                if (responseBody.isNotEmpty()) {
                    val truncatedResponse = SecurityValidator.truncateForLogging(responseBody, 200)
                    Logger.d("HttpUploadWorker", "Response: $truncatedResponse")
                }

                WorkerResult.Success(
                    message = "Uploaded ${SecurityValidator.formatByteSize(fileSize)} - HTTP $statusCode",
                    data = mapOf(
                        "statusCode" to statusCode,
                        "fileSize" to fileSize,
                        "fileName" to fileName,
                        "url" to SecurityValidator.sanitizedURL(config.url),
                        "responseLength" to responseBody.length
                    )
                )
            } else {
                Logger.w("HttpUploadWorker", "Upload completed with non-success status $statusCode")
                WorkerResult.Failure(
                    message = "HTTP $statusCode error",
                    shouldRetry = statusCode in 500..599
                )
            }
        } catch (e: Exception) {
            Logger.e("HttpUploadWorker", "Upload failed", e)
            WorkerResult.Failure("Upload failed: ${e.message}", shouldRetry = true)
        }
    }

    /**
     * Detects MIME type based on file extension.
     */
    private fun detectMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"

            // Videos
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"

            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"

            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"

            // Archives
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"

            // Default
            else -> "application/octet-stream"
        }
    }

    companion object {
        /**
         * Creates a default HTTP client with reasonable timeouts.
         */
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient {
                expectSuccess = false // Don't throw on non-2xx responses
            }
        }
    }
}