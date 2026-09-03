package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the manual redirect interceptor's two security properties: every
 * hop is re-validated against [SecurityValidator.validateURL] (not just the initial URL), and
 * credential headers are stripped on a cross-origin hop. See [installSecureRedirectFollowing]
 * for why this can't just be Ktor's built-in `HttpRedirect` plugin.
 */
class SecureRedirectFollowingTest {

    private fun clientFor(engine: MockEngine) =
        HttpClient(engine) { install(HttpTimeout) }.installSecureRedirectFollowing()

    @Test
    fun redirectToPrivateIp_isBlocked_evenThoughInitialUrlWasSafe() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine { request ->
            requestCount++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://169.254.169.254/latest/meta-data/")
            )
        }
        val client = clientFor(mockEngine)

        val error = assertFailsWith<IllegalStateException> {
            client.get("https://safe.example.com/redirect-me")
        }
        assertTrue(error.message.orEmpty().contains("Redirect to unsafe URL blocked"))
        // The malicious hop must never actually be dispatched to the engine.
        assertEquals(1, requestCount)
    }

    @Test
    fun crossOriginRedirect_stripsAuthorizationAndCookie() = runTest {
        var secondRequestAuthHeader: String? = null
        var secondRequestCookieHeader: String? = null
        var hop = 0
        val mockEngine = MockEngine { request ->
            hop++
            if (hop == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://other-host.example.com/final")
                )
            } else {
                secondRequestAuthHeader = request.headers[HttpHeaders.Authorization]
                secondRequestCookieHeader = request.headers[HttpHeaders.Cookie]
                respond(content = "ok", status = HttpStatusCode.OK)
            }
        }
        val client = clientFor(mockEngine)

        val response = client.get("https://original-host.example.com/start") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            header(HttpHeaders.Cookie, "session=secret-session")
        }

        assertEquals("ok", response.bodyAsText())
        assertEquals(2, hop)
        assertNull(secondRequestAuthHeader, "Authorization must not leak to a different host")
        assertNull(secondRequestCookieHeader, "Cookie must not leak to a different host")
    }

    @Test
    fun sameOriginRedirect_preservesAuthorization() = runTest {
        var secondRequestAuthHeader: String? = null
        var hop = 0
        val mockEngine = MockEngine { request ->
            hop++
            if (hop == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://same-host.example.com/final")
                )
            } else {
                secondRequestAuthHeader = request.headers[HttpHeaders.Authorization]
                respond(content = "ok", status = HttpStatusCode.OK)
            }
        }
        val client = clientFor(mockEngine)

        client.get("https://same-host.example.com/start") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
        }

        assertEquals("Bearer secret-token", secondRequestAuthHeader)
    }
}
