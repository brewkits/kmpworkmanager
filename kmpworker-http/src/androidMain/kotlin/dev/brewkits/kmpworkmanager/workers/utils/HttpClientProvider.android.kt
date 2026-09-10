package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
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

                // TLS pinning, when the host configured it. Delegated to OkHttp's
                // CertificatePinner rather than hand-rolled: it already does SPKI hashing,
                // chain traversal and wildcard matching, and getting any of those subtly wrong
                // yields a pinner that accepts everything — worse than no pinning, because it
                // reads as protection. Absent configuration this block adds nothing and the
                // client is byte-for-byte what it was before.
                val pins = TlsPinningConfig.pins
                if (pins.isNotEmpty()) {
                    certificatePinner(
                        okhttp3.CertificatePinner.Builder().apply {
                            pins.forEach { entry ->
                                entry.sha256Pins.forEach { pin -> add(entry.hostname, pin) }
                            }
                        }.build()
                    )
                    Logger.i(
                        LogTags.WORKER,
                        "TLS pinning enabled for ${pins.size} host(s): " +
                            pins.joinToString { it.hostname }
                    )
                }
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
            header("User-Agent", "KmpWorkManager/$LIBRARY_VERSION")
            header("Connection", "keep-alive")
        }
    }.installSecureRedirectFollowing()
}
