package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
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

        // Disable Ktor's automatic HttpRedirect plugin — redirects are followed manually
        // in the HttpSend interceptor below so each hop is validated by SecurityValidator.
        followRedirects = false

        // Default request headers
        defaultRequest {
            header("User-Agent", "KmpWorkManager/2.3.4")
            header("Connection", "keep-alive")
        }
    }.also { client ->
        // Security-aware redirect following: validate each Location URL before following.
        // Prevents SSRF attacks where the initial URL passes validation but a redirect
        // targets a private/loopback address (e.g. 302 → http://169.254.169.254/...).
        client.plugin(HttpSend).intercept { request ->
            var call = execute(request)
            var hops = 0
            while (call.response.status.value in 301..308 && hops++ < 10) {
                val location = call.response.headers[HttpHeaders.Location] ?: break
                if (!SecurityValidator.validateURL(location)) {
                    throw IllegalStateException(
                        "Redirect to unsafe URL blocked: ${SecurityValidator.sanitizedURL(location)}"
                    )
                }
                val redirectRequest = HttpRequestBuilder().apply {
                    takeFrom(request)
                    url(location)
                    // Strip credential headers on cross-origin redirects (RFC 7235 §3.1).
                    // takeFrom() copies ALL headers including Authorization and Cookie — sending
                    // these to a different host leaks credentials to an unintended server.
                    val originalHost = request.url.host
                    val redirectHost = URLBuilder(location).host
                    if (originalHost != redirectHost) {
                        headers.remove(HttpHeaders.Authorization)
                        headers.remove(HttpHeaders.Cookie)
                    }
                }
                call = execute(redirectRequest)
            }
            call
        }
    }
}
