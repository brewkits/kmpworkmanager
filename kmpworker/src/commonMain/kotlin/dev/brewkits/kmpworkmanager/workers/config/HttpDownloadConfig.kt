package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for HttpDownloadWorker.
 *
 * @property url The HTTP/HTTPS URL to download from
 * @property savePath Absolute path where to save the downloaded file
 * @property headers Optional HTTP headers
 * @property timeoutMs Download timeout in milliseconds (default: 300000ms = 5 minutes)
 */
@Serializable
data class HttpDownloadConfig(
    val url: String,
    val savePath: String,
    val headers: Map<String, String>? = null,
    val timeoutMs: Long = 300000
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(savePath.isNotBlank()) {
            "Save path cannot be blank"
        }
        require(timeoutMs > 0) {
            "Timeout must be positive"
        }
    }
}
