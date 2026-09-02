package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.TokenRefreshConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.IOException

private const val TAG = "TokenRefresh"

/**
 * Executes [request] (the original request); if it comes back `401` and [config] is
 * non-null, refreshes the token via [config] and retries [request] **exactly once** with
 * the new token. Never loops: a still-401 retry response, a failed/non-2xx refresh call,
 * or a refresh response body that doesn't contain [TokenRefreshConfig.tokenResponsePath]
 * are all returned/passed through as the final result rather than retrying again.
 *
 * @param request Executes and returns the original request. Receives the new token (or
 *   `null` on the first call) so the caller can inject it into the appropriate header —
 *   this function does not assume where in the request the auth header lives beyond what
 *   [TokenRefreshConfig.authHeaderName] says, since the caller may also need to re-apply
 *   HMAC signing over the refreshed header.
 */
internal suspend fun executeWithTokenRefresh(
    client: HttpClient,
    config: TokenRefreshConfig?,
    request: suspend (newToken: String?) -> HttpResponse
): HttpResponse {
    val first = request(null)
    if (config == null || first.status != HttpStatusCode.Unauthorized) return first

    val newToken = refreshToken(client, config)
    if (newToken == null) {
        Logger.w(TAG, "Token refresh failed or returned no usable token — returning original 401")
        return first
    }

    Logger.i(TAG, "Token refreshed — retrying original request once")
    return request(newToken)
}

private suspend fun refreshToken(client: HttpClient, config: TokenRefreshConfig): String? {
    // Belt-and-suspenders SSRF gate: HttpRequestWorker validates refreshUrl in doWork()
    // before this ever runs, but that gate lives in the caller, not here. Checking again
    // makes this function safe by construction for any future caller (HttpSyncWorker,
    // HttpUploadWorker, …) that adopts TokenRefreshConfig without remembering to
    // replicate the same check — the exact drift class fixed in installSecureRedirectFollowing().
    if (!SecurityValidator.validateURL(config.refreshUrl)) {
        Logger.e(TAG, "Refusing to call unsafe refresh URL: ${SecurityValidator.sanitizedURL(config.refreshUrl)}")
        return null
    }
    return try {
        val response = client.request(config.refreshUrl) {
            method = HttpMethod.parse(config.refreshMethod)
            config.refreshHeaders?.forEach { (key, value) -> header(key, value) }
            if (config.refreshBody != null) {
                setBody(config.refreshBody)
                contentType(ContentType.Application.Json)
            }
        }
        if (response.status.value !in 200..299) {
            Logger.w(TAG, "Refresh endpoint returned ${response.status.value} — not retrying original request")
            return null
        }
        val json = Json.parseToJsonElement(readBoundedBody(response, SecurityValidator.MAX_RESPONSE_BODY_SIZE.toLong()))
        // Blank counts as "no usable token" — an empty access_token would otherwise send
        // "Authorization: Bearer " on retry, a request the server will reject anyway but
        // that masks the real failure (a malformed/incomplete refresh response) behind a
        // second, misleading 401.
        extractByDotPath(json, config.tokenResponsePath)?.takeUnless { it.isBlank() }
    } catch (e: Exception) {
        Logger.e(TAG, "Token refresh call to ${SecurityValidator.sanitizedURL(config.refreshUrl)} failed", e)
        null
    }
}

/**
 * Reads [response]'s body through a bounded channel loop instead of [bodyAsText] — a
 * misbehaving or compromised refresh endpoint returning an unbounded body must not be
 * allowed to buffer arbitrarily large amounts of RAM. Mirrors the accumulate-and-throw
 * pattern in `HttpDownloadWorker`'s streaming loop (checking bytes actually read rather
 * than trusting `Content-Length`, which a malicious server can lie about or omit).
 */
private suspend fun readBoundedBody(response: HttpResponse, maxBytes: Long): String {
    val channel = response.bodyAsChannel()
    val chunk = ByteArray(8192)
    val accumulated = Buffer()
    var total = 0L
    while (!channel.isClosedForRead) {
        val bytesRead = channel.readAvailable(chunk)
        if (bytesRead == -1) break
        if (bytesRead > 0) {
            total += bytesRead
            if (total > maxBytes) {
                throw IOException("Token refresh response too large: exceeds limit of $maxBytes bytes")
            }
            accumulated.write(chunk, 0, bytesRead)
        }
    }
    return accumulated.readUtf8()
}

/**
 * Navigates [root] through [path]'s dot-separated keys (e.g. `"auth.access_token"` into
 * `{"auth":{"access_token":"..."}}`) and returns the string content of the value found
 * there, or `null` if any segment is missing, not an object, or the final value isn't a
 * JSON string/primitive.
 */
internal fun extractByDotPath(root: JsonElement, path: String): String? {
    var current: JsonElement = root
    for (key in path.split('.')) {
        val obj = current as? JsonObject ?: return null
        current = obj[key] ?: return null
    }
    val primitive = current as? JsonPrimitive ?: return null
    return primitive.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    }
}
