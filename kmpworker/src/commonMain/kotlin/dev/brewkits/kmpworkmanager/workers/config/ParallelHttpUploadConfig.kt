package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Single file in a [ParallelHttpUploadConfig] batch.
 *
 * @property filePath Absolute path of the file to upload.
 * @property fieldName Multipart form field name for this file. Defaults to `"file"`.
 * @property fileName Optional override of the filename sent in `Content-Disposition`.
 *   Defaults to the basename of [filePath].
 * @property mimeType Optional `Content-Type` for this file part. Defaults to
 *   `application/octet-stream`.
 */
@Serializable
data class ParallelUploadFile(
    val filePath: String,
    val fieldName: String = "file",
    val fileName: String? = null,
    val mimeType: String? = null
) {
    init {
        require(filePath.isNotBlank()) { "filePath must not be blank" }
        require(fieldName.isNotBlank()) { "fieldName must not be blank" }
    }
}

/**
 * Configuration for [ParallelHttpUploadWorker] — multi-file upload where each file is a
 * **separate** HTTP request running concurrently.
 *
 * Differs from [HttpUploadConfig] in that the latter bundles every file into a single
 * multipart body. This config issues one POST per file (Flutter parity) which:
 * - Lets the server respond per-file (status, body, ETag).
 * - Retries an individual file's network failure without re-sending already-succeeded ones.
 * - Bounds per-host connection use via [maxConcurrent].
 *
 * @property url Endpoint that receives one multipart-form-data POST per file.
 * @property files Files to upload. Must contain at least one entry.
 * @property headers Headers added to **every** request (e.g. `Authorization`).
 * @property fields Additional multipart form fields added to **every** request.
 * @property maxConcurrent Maximum simultaneous requests against the same host. 1..16,
 *   default 3. Higher counts saturate the connection pool and trigger backend rate-limits.
 * @property maxRetries How many times to retry a file that returns 5xx or fails with a
 *   network error. 0..5, default 1. 4xx responses are NOT retried (programming /
 *   authentication errors).
 * @property timeoutMs Per-file request timeout in ms. Default 5 minutes.
 */
@Serializable
data class ParallelHttpUploadConfig(
    val url: String,
    val files: List<ParallelUploadFile>,
    val headers: Map<String, String>? = null,
    val fields: Map<String, String>? = null,
    val maxConcurrent: Int = 3,
    val maxRetries: Int = 1,
    val timeoutMs: Long = 300_000L
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(files.isNotEmpty()) { "files must not be empty" }
        require(maxConcurrent in 1..16) {
            "maxConcurrent must be between 1 and 16 (got $maxConcurrent)"
        }
        require(maxRetries in 0..5) {
            "maxRetries must be between 0 and 5 (got $maxRetries)"
        }
        require(timeoutMs > 0) { "Timeout must be positive" }
    }
}

/**
 * Per-file outcome returned by [ParallelHttpUploadWorker] in `WorkerResult.Success.data`
 * (as a JSON array under `"fileResults"`).
 */
@Serializable
data class ParallelUploadFileResult(
    val filePath: String,
    val success: Boolean,
    val statusCode: Int? = null,
    val attempts: Int = 0,
    val bytesSent: Long = 0,
    val error: String? = null
)
