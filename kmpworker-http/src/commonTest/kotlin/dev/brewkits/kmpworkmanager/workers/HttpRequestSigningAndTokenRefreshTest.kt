package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.config.HmacSigningConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.config.TokenRefreshConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.workers.utils.computeHmacSignature
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [HttpRequestWorker]'s HMAC signing ([HmacSigningConfig]) and
 * token-refresh-on-401 ([TokenRefreshConfig]) integration — issues #78/#81.
 */
class HttpRequestSigningAndTokenRefreshTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }

    // ==================== HMAC signing ====================

    @Test
    fun hmacSigning_addsSignatureAndTimestampHeaders_matchingIndependentComputation() = runTest {
        var observedSignature: String? = null
        var observedTimestamp: String? = null
        val mock = MockEngine { request ->
            observedSignature = request.headers["X-Signature"]
            observedTimestamp = request.headers["X-Timestamp"]
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val signing = HmacSigningConfig(secretKey = "0123456789abcdef")
        val config = HttpRequestConfig(url = "https://api.example.com/things", method = "POST", body = "payload", hmacSigning = signing)

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Success, "expected Success, got: $result")
        assertNotNull(observedSignature, "X-Signature header must be present")
        assertNotNull(observedTimestamp, "X-Timestamp header must be present when includeTimestamp is true (default)")
        val expected = computeHmacSignature("POST", config.url, config.body, observedTimestamp, signing)
        assertEquals(expected, observedSignature, "signature must match independently recomputing over the same inputs")
    }

    @Test
    fun hmacSigning_includeTimestampFalse_omitsTimestampHeader() = runTest {
        var sawTimestampHeader = false
        val mock = MockEngine { request ->
            sawTimestampHeader = request.headers.contains("X-Timestamp")
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val signing = HmacSigningConfig(secretKey = "0123456789abcdef", includeTimestamp = false)
        val config = HttpRequestConfig(url = "https://api.example.com/things", hmacSigning = signing)

        worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(!sawTimestampHeader)
    }

    // ==================== Token refresh ====================

    @Test
    fun tokenRefresh_on401_refreshesAndRetriesOnce_thenSucceeds() = runTest {
        var callCount = 0
        val mock = MockEngine { request ->
            when {
                request.url.encodedPath == "/refresh" -> {
                    callCount++
                    respond(
                        content = """{"auth":{"access_token":"new-token-123"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                request.headers[HttpHeaders.Authorization] == "Bearer new-token-123" -> {
                    callCount++
                    respond(content = "protected data", status = HttpStatusCode.OK)
                }
                else -> {
                    callCount++
                    respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
                }
            }
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val refresh = TokenRefreshConfig(
            refreshUrl = "https://api.example.com/refresh",
            tokenResponsePath = "auth.access_token"
        )
        val config = HttpRequestConfig(
            url = "https://api.example.com/protected",
            headers = mapOf("Authorization" to "Bearer stale-token"),
            tokenRefresh = refresh
        )

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Success, "expected Success after refresh+retry, got: $result")
        assertEquals(3, callCount, "expected exactly 3 calls: original 401, refresh, retried request")
    }

    @Test
    fun tokenRefresh_stillUnauthorizedAfterRetry_doesNotLoop() = runTest {
        var protectedCallCount = 0
        var refreshCallCount = 0
        val mock = MockEngine { request ->
            when {
                request.url.encodedPath == "/refresh" -> {
                    refreshCallCount++
                    respond(
                        content = """{"access_token":"new-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> {
                    protectedCallCount++
                    respond(content = "still unauthorized", status = HttpStatusCode.Unauthorized)
                }
            }
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val config = HttpRequestConfig(
            url = "https://api.example.com/protected",
            tokenRefresh = TokenRefreshConfig(refreshUrl = "https://api.example.com/refresh")
        )

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure, "still-401 after retry must surface as Failure, got: $result")
        assertEquals(1, refreshCallCount, "refresh must be attempted exactly once — no retry loop")
        assertEquals(2, protectedCallCount, "protected endpoint hit exactly twice: original + one retry")
    }

    @Test
    fun tokenRefresh_refreshEndpointFails_returnsOriginal401_withoutRetrying() = runTest {
        var protectedCallCount = 0
        val mock = MockEngine { request ->
            if (request.url.encodedPath == "/refresh") {
                respond(content = "server error", status = HttpStatusCode.InternalServerError)
            } else {
                protectedCallCount++
                respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
            }
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val config = HttpRequestConfig(
            url = "https://api.example.com/protected",
            tokenRefresh = TokenRefreshConfig(refreshUrl = "https://api.example.com/refresh")
        )

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure)
        assertEquals(1, protectedCallCount, "must not retry the original request when refresh itself failed")
    }

    @Test
    fun noTokenRefreshConfig_401PassesThroughUnchanged_noRefreshCallAttempted() = runTest {
        var callCount = 0
        val mock = MockEngine { _ ->
            callCount++
            respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val config = HttpRequestConfig(url = "https://api.example.com/protected") // tokenRefresh = null

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure)
        assertEquals(1, callCount, "with no tokenRefresh config, exactly one call — no refresh attempted")
    }

    @Test
    fun tokenRefresh_ssrfBlockedRefreshUrl_failsBeforeAnyRequest() = runTest {
        var callCount = 0
        // MockEngine that fails the test if invoked — proves the SSRF gate on refreshUrl
        // rejects the config before ANY network call, primary request included.
        val mock = MockEngine { _ ->
            callCount++
            respond(content = "should not be called", status = HttpStatusCode.OK)
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val config = HttpRequestConfig(
            url = "https://api.example.com/protected",
            // Cloud-metadata link-local address — the same one BuiltinWorkersTest uses to
            // pin SecurityValidator's SSRF blocklist for the primary URL.
            tokenRefresh = TokenRefreshConfig(refreshUrl = "http://169.254.169.254/latest/meta-data/")
        )

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure)
        assertEquals("Invalid or unsafe refresh URL", result.message)
        assertEquals(0, callCount, "no request — neither primary nor refresh — may be issued when refreshUrl is SSRF-blocked")
    }

    @Test
    fun tokenRefresh_blankToken_treatedAsFailedRefresh_doesNotRetryWithEmptyBearer() = runTest {
        var protectedCallCount = 0
        val mock = MockEngine { request ->
            when {
                request.url.encodedPath == "/refresh" -> respond(
                    content = """{"access_token":""}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> {
                    protectedCallCount++
                    assertTrue(
                        request.headers[HttpHeaders.Authorization] != "Bearer ",
                        "must never send an empty-token Authorization header"
                    )
                    respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
                }
            }
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val config = HttpRequestConfig(
            url = "https://api.example.com/protected",
            tokenRefresh = TokenRefreshConfig(refreshUrl = "https://api.example.com/refresh")
        )

        val result = worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure)
        assertEquals(1, protectedCallCount, "a blank refreshed token must not trigger a retry")
    }

    // ==================== HMAC signing does not cover the request body on GET ====================

    @Test
    fun hmacSigning_getRequestWithConfigBody_signatureExcludesBody_sinceBodyIsNeverSent() = runTest {
        var observedSignature: String? = null
        var observedTimestamp: String? = null
        val mock = MockEngine { request ->
            observedSignature = request.headers["X-Signature"]
            observedTimestamp = request.headers["X-Timestamp"]
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val signing = HmacSigningConfig(secretKey = "0123456789abcdef")
        // GET with a body: HttpRequestWorker never calls setBody for GET, so the signature
        // must be computed as if body were null — not over this (unsent) value.
        val config = HttpRequestConfig(url = "https://api.example.com/things", method = "GET", body = "never-sent", hmacSigning = signing)

        worker.doWork(HttpWorkerJson.encodeToString(config), WorkerEnvironment(null) { false })

        val expected = computeHmacSignature("GET", config.url, null, observedTimestamp, signing)
        assertEquals(expected, observedSignature)
    }
}
