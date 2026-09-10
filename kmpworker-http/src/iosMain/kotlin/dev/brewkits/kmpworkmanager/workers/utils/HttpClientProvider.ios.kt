package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.header
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

            // TLS pinning, and only when the host asked for it. Installing a challenge handler
            // unconditionally would route every server-trust challenge through our code even
            // for unpinned hosts — more surface, and a bug in it would break TLS for apps that
            // never wanted pinning. With no pins configured this block does not run and the
            // client is exactly what it was before.
            if (TlsPinningConfig.pins.isNotEmpty()) {
                handleChallenge { _, _, challenge, completionHandler ->
                    // Integer literals, not NSURLSessionAuthChallenge* constants, and this is
                    // not laziness. The disposition is Long in the per-target compilations and
                    // Int in the shared iOS metadata compilation, while the platform constants
                    // are Long in both — so passing a constant compiles for the simulator and
                    // fails `compileIosMainKotlinMetadata`, which only runs at publish time.
                    // A literal adapts to whichever width the expected type has.
                    //
                    // NSURLSessionAuthChallengePerformDefaultHandling == 1
                    // NSURLSessionAuthChallengeCancelAuthenticationChallenge == 2
                    when (decidePinningChallenge(challenge)) {
                        PinDecision.PROCEED_WITH_DEFAULT_HANDLING -> completionHandler(1, null)
                        PinDecision.CANCEL -> completionHandler(2, null)
                    }
                }
                Logger.i(
                    LogTags.WORKER,
                    "TLS pinning enabled for ${TlsPinningConfig.pins.size} host(s): " +
                        TlsPinningConfig.pins.joinToString { it.hostname }
                )
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
