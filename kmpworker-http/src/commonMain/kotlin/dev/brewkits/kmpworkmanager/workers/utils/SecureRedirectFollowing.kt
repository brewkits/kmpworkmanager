package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder

/**
 * Installs security-aware manual redirect following on an [HttpClient] whose engine has
 * native redirect-following disabled (`followRedirects(false)` on OkHttp,
 * `followRedirects = false` on the Ktor config — both platform providers set this before
 * calling this function).
 *
 * **Why manual, not Ktor's built-in `HttpRedirect` plugin:** the built-in plugin follows a
 * `Location` header unconditionally. `SecurityValidator.validateURL()` only runs once,
 * against the caller-supplied initial URL — a redirect response could point at a
 * private/loopback/CGNAT address (e.g. `302 → http://169.254.169.254/latest/meta-data/`)
 * that the initial check never sees, defeating the SSRF blocklist entirely. This
 * interceptor re-validates every hop before following it, up to 10 hops, and strips
 * `Authorization`/`Cookie` on any cross-origin hop (RFC 7235 §3.1) — `takeFrom()` copies
 * every header including credentials, which must not leak to a different host.
 *
 * **Why this lives in commonMain:** until this fix, both `HttpClientProvider.android.kt`
 * and `HttpClientProvider.ios.kt` carried an identical, independently-copied ~25-line
 * version of this exact interceptor. Being security-critical logic, two copies risked
 * silently drifting apart on a future change (e.g. adjusting the hop limit, or adding a
 * header to strip, in only one file). One shared implementation removes that risk instead
 * of merely documenting it.
 */
internal fun HttpClient.installSecureRedirectFollowing(): HttpClient {
    plugin(HttpSend).intercept { request ->
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
    return this
}
