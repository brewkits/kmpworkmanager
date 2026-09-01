package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.workers.config.TokenRefreshConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-function coverage for [extractByDotPath] — no HTTP client involved.
 */
class TokenRefreshTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun topLevelKey_extractsDirectly() {
        assertEquals("abc123", extractByDotPath(json("""{"access_token":"abc123"}"""), "access_token"))
    }

    @Test
    fun nestedKey_navigatesThroughDots() {
        assertEquals("abc123", extractByDotPath(json("""{"auth":{"access_token":"abc123"}}"""), "auth.access_token"))
    }

    @Test
    fun deeplyNestedKey_navigatesThroughMultipleLevels() {
        val doc = json("""{"a":{"b":{"c":{"d":"deep-value"}}}}""")
        assertEquals("deep-value", extractByDotPath(doc, "a.b.c.d"))
    }

    @Test
    fun missingKey_atTopLevel_returnsNull() {
        assertNull(extractByDotPath(json("""{"other":"x"}"""), "access_token"))
    }

    @Test
    fun missingKey_partwayThroughPath_returnsNull() {
        assertNull(extractByDotPath(json("""{"auth":{"other":"x"}}"""), "auth.access_token"))
    }

    @Test
    fun nonObjectMidPath_returnsNull_ratherThanThrowing() {
        // "auth" is a string, not an object — trying to navigate ".access_token" into it
        // must fail gracefully, not throw a ClassCastException from a malformed/unexpected
        // refresh response shape.
        assertNull(extractByDotPath(json("""{"auth":"not-an-object"}"""), "auth.access_token"))
    }

    @Test
    fun jsonNullValue_returnsNull() {
        assertNull(extractByDotPath(json("""{"access_token":null}"""), "access_token"))
    }

    @Test
    fun numericValue_returnsItsStringContent() {
        // Some backends return a numeric token/ID; JsonPrimitive.content still yields its
        // textual form, so this is treated as usable rather than silently failing.
        assertEquals("12345", extractByDotPath(json("""{"access_token":12345}"""), "access_token"))
    }

    @Test
    fun arrayValue_returnsNull_notAToStringDump() {
        assertNull(extractByDotPath(json("""{"access_token":["a","b"]}"""), "access_token"))
    }

    @Test
    fun emptyPath_returnsNull() {
        assertNull(extractByDotPath(json("""{"access_token":"abc"}"""), ""))
    }

    // ==================== executeWithTokenRefresh: SSRF gate is inside refreshToken(), ====================
    // ==================== not only in HttpRequestWorker.doWork() ====================

    /**
     * Calls [executeWithTokenRefresh] directly — bypassing `HttpRequestWorker.doWork()`'s
     * own `validateURL(refreshUrl)` check entirely — to prove the gate inside
     * `refreshToken()` itself rejects an SSRF-blocked refresh URL. A test that only went
     * through `HttpRequestWorker` would pass on the location of the check, not the check;
     * this pins the check at the layer any future `TokenRefreshConfig` caller shares.
     */
    @Test
    fun executeWithTokenRefresh_ssrfBlockedRefreshUrl_neverCallsRefreshEndpoint() = runTest {
        var refreshEndpointCallCount = 0
        val mock = MockEngine { request ->
            if (request.url.host == "169.254.169.254") {
                refreshEndpointCallCount++
                respond(content = "should never be reached", status = HttpStatusCode.OK)
            } else {
                respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
            }
        }
        val client = HttpClient(mock) { install(HttpTimeout) }
        val config = TokenRefreshConfig(refreshUrl = "http://169.254.169.254/latest/meta-data/")

        var originalRequestCallCount = 0
        val response: HttpResponse = executeWithTokenRefresh(client, config) {
            originalRequestCallCount++
            client.request("https://api.example.com/protected") { }
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status, "original 401 must be returned when refresh is blocked")
        assertEquals(0, refreshEndpointCallCount, "refreshToken() must reject the SSRF-blocked URL before any HTTP call")
        assertEquals(1, originalRequestCallCount, "no retry — refresh never produced a token")
    }
}
