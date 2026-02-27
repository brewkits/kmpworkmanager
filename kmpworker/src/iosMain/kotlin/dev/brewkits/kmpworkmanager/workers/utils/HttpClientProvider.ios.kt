package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * iOS-specific HttpClient creation using Darwin engine (NSURLSession).
 *
 * Configuration:
 * - Engine: Darwin with optimized session settings
 * - 30-second timeout interval
 * - Default challenge handling for SSL
 * - HTTP/2 support (enabled by iOS by default)
 * - Maximum 20 connections per host
 * - Gzip/Deflate compression
 * - JSON content negotiation
 */
internal actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        // Darwin engine configuration
        engine {
            // Configure request timeout
            configureRequest {
                setTimeoutInterval(30.0)
            }

            // Configure session
            configureSession {
                setAllowsCellularAccess(true)
                setHTTPShouldSetCookies(true)
                setHTTPShouldUsePipelining(true)
                setHTTPMaximumConnectionsPerHost(20)
            }
        }

        // HTTP timeout configuration
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }

        // Gzip/Deflate compression
        install(ContentEncoding) {
            gzip()
            deflate()
        }

        // JSON content negotiation
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            })
        }

        // Don't throw on non-2xx responses
        expectSuccess = false

        // Follow redirects automatically
        followRedirects = true

        // Default request headers
        defaultRequest {
            header("User-Agent", "KmpWorkManager/2.3.4")
            header("Connection", "keep-alive")
        }
    }
}
