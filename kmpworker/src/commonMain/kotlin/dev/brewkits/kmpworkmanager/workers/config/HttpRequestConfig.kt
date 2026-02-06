package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for HttpRequestWorker.
 *
 * @property url The HTTP/HTTPS URL to request
 * @property method HTTP method (GET, POST, PUT, DELETE, PATCH)
 * @property headers Optional HTTP headers
 * @property body Optional request body (for POST, PUT, PATCH)
 * @property timeoutMs Request timeout in milliseconds (default: 30000ms = 30s)
 */
@Serializable
data class HttpRequestConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val timeoutMs: Long = 30000
) {
    val httpMethod: HttpMethod
        get() = HttpMethod.fromString(method)

    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(timeoutMs > 0) {
            "Timeout must be positive"
        }
    }
}
