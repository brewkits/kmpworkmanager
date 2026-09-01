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
 * @property hmacSigning Optional HMAC-SHA256 request signing — see [HmacSigningConfig]. The
 *   signature covers `METHOD`/`URL`/`BODY`/`TIMESTAMP` only, **not headers** — it does not
 *   cover [headers] or a refreshed `Authorization` header from [tokenRefresh].
 * @property tokenRefresh Optional automatic token refresh on `401` — see
 *   [TokenRefreshConfig]. When [hmacSigning] is also set, the retried request is re-signed
 *   with a fresh timestamp (so its signature differs from the original attempt's), but the
 *   signature still does not cover the refreshed `Authorization` header — see [hmacSigning].
 */
@Serializable
data class HttpRequestConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val timeoutMs: Long = 30000,
    val hmacSigning: HmacSigningConfig? = null,
    val tokenRefresh: TokenRefreshConfig? = null
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
