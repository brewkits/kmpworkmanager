package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Supported HTTP methods for built-in HTTP workers.
 */
@Serializable
enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH;

    companion object {
        fun fromString(method: String): HttpMethod {
            return when (method.uppercase()) {
                "GET" -> GET
                "POST" -> POST
                "PUT" -> PUT
                "DELETE" -> DELETE
                "PATCH" -> PATCH
                else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
            }
        }
    }
}
