package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Android-specific HttpClient creation using OkHttp engine.
 *
 * Configuration:
 * - Engine: OkHttp with connection pool (50 max connections, 5-minute keep-alive)
 * - Timeouts: 30 seconds for connect/read/write
 * - Retry on connection failure enabled
 * - HTTP/2 support enabled by default
 * - Gzip/Deflate compression
 * - JSON content negotiation
 */
internal actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        // OkHttp engine configuration
        engine {
            config {
                // Connection pool configuration (OkHttp 4.x API)
                connectionPool(
                    connectionPool = okhttp3.ConnectionPool(
                        maxIdleConnections = 50,
                        keepAliveDuration = 5,
                        timeUnit = TimeUnit.MINUTES
                    )
                )

                // Timeout configuration (30 seconds each)
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)

                // Retry on connection failure
                retryOnConnectionFailure(true)

                // Disable engine-level redirect following — validated manually via HttpSend interceptor below
                followRedirects(false)
                followSslRedirects(false)
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
                }
                call = execute(redirectRequest)
            }
            call
        }
    }
}
