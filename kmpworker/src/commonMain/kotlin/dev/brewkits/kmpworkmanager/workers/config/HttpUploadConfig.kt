package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for HttpUploadWorker.
 *
 * @property url The HTTP/HTTPS URL to upload to
 * @property filePath Absolute path to the file to upload
 * @property fileFieldName Form field name for the file (default: "file")
 * @property fileName Override the uploaded filename (optional)
 * @property mimeType Override MIME type (optional, auto-detected if not provided)
 * @property headers Optional HTTP headers
 * @property fields Additional form fields to include
 * @property timeoutMs Upload timeout in milliseconds (default: 120000ms = 2 minutes)
 */
@Serializable
data class HttpUploadConfig(
    val url: String,
    val filePath: String,
    val fileFieldName: String = "file",
    val fileName: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String>? = null,
    val fields: Map<String, String>? = null,
    val timeoutMs: Long = 120000
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(filePath.isNotBlank()) {
            "File path cannot be blank"
        }
        require(fileFieldName.isNotBlank()) {
            "File field name cannot be blank"
        }
        require(timeoutMs > 0) {
            "Timeout must be positive"
        }
    }
}
