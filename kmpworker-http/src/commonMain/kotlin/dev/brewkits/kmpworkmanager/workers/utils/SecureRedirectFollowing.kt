package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom as takeFromUrlString

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
        // Tracks the request actually in flight. Reassigning it each hop is load-bearing
        // twice over, and both halves were wrong while this stayed pinned to the initial
        // request: (1) a relative `Location` on hop >= 2 resolved against the *original*
        // host, so a third-party redirect could steer the request back onto the origin at a
        // path of its choosing; (2) `takeFrom(request)` re-copied the `Authorization`/`Cookie`
        // headers from the original request, restoring credentials that an earlier
        // cross-origin hop had deliberately stripped. Chaining from the previous hop's
        // builder makes the strip sticky — once dropped, a header cannot come back.
        var currentRequest = request
        var call = execute(currentRequest)
        var hops = 0
        while (call.response.status.value in 301..308 && hops++ < 10) {
            val locationHeader = call.response.headers[HttpHeaders.Location] ?: break

            // Location may be relative (RFC 7231 §7.1.2 — e.g. "/v2/resource"), which is
            // common behind reverse proxies. SecurityValidator.validateURL requires an
            // absolute http(s):// URL, so validating the raw header would reject every
            // legitimate same-origin relative redirect. Resolve it against the current
            // request's URL first — URLBuilder.takeFrom(String) keeps the base's
            // protocol/host/port when the string omits them (a relative path), exactly
            // like Ktor's own built-in HttpRedirect plugin resolves Location headers.
            val location = URLBuilder(currentRequest.url).apply { takeFromUrlString(locationHeader) }.buildString()

            if (!SecurityValidator.validateURL(location)) {
                throw IllegalStateException(
                    "Redirect to unsafe URL blocked: ${SecurityValidator.sanitizedURL(location)}"
                )
            }
            val redirectRequest = HttpRequestBuilder().apply {
                takeFrom(currentRequest)
                url(location)
                // Strip credential headers on cross-origin redirects (RFC 7235 §3.1).
                // takeFrom() copies ALL headers including Authorization and Cookie — sending
                // these to a different host leaks credentials to an unintended server.
                // Compared against the *previous hop's* host, not the original: a chain that
                // has already left the origin must not regain credentials by bouncing back.
                val previousHost = currentRequest.url.host
                val redirectHost = URLBuilder(location).host
                if (previousHost != redirectHost) {
                    headers.remove(HttpHeaders.Authorization)
                    headers.remove(HttpHeaders.Cookie)
                }
            }
            currentRequest = redirectRequest
            call = execute(currentRequest)
        }
        call
    }
    return this
}
