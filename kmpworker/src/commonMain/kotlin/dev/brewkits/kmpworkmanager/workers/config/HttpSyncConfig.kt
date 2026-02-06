package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Configuration for HttpSyncWorker.
 *
 * @property url The HTTP/HTTPS URL for synchronization endpoint
 * @property method HTTP method (GET, POST, PUT, PATCH) - default: POST
 * @property headers Optional HTTP headers
 * @property requestBody Optional JSON request body (will be serialized automatically)
 * @property timeoutMs Request timeout in milliseconds (default: 60000ms = 1 minute)
 */
@Serializable
data class HttpSyncConfig(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String>? = null,
    val requestBody: JsonElement? = null,
    val timeoutMs: Long = 60000
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
        val supportedMethods = setOf("GET", "POST", "PUT", "PATCH")
        require(method.uppercase() in supportedMethods) {
            "Method must be one of: ${supportedMethods.joinToString()}"
        }
    }
}
