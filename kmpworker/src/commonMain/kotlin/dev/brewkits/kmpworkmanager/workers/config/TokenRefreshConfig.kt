package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Automatic token refresh on a `401 Unauthorized` response, applied by
 * [dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker]. Mirrors the Flutter
 * `native_workmanager` `token_refresh_config.dart` config so a payload shared between the
 * two scheduling layers parses identically.
 *
 * Flow: the original request is sent as configured. If (and only if) it comes back `401`,
 * the worker issues one request to [refreshUrl], extracts a new token from the JSON
 * response body via [tokenResponsePath] (dot-notation, e.g. `"auth.access_token"` for
 * `{"auth":{"access_token":"..."}}`), then retries the **original** request exactly once
 * with the new token injected into [authHeaderName] as `"$authHeaderPrefix$token"`. There
 * is no refresh loop: a still-401 retry, a non-2xx refresh response, or a response body
 * that doesn't contain [tokenResponsePath] all fall through to the original 401 response
 * rather than retrying again.
 *
 * **Security note:** any refresh token or client secret in [refreshBody]/[refreshHeaders]
 * is persisted as part of the worker's task input (same as an `Authorization` header value
 * passed via `HttpRequestConfig.headers` today) so it survives process death for retries.
 * Do not use a secret that must never touch disk.
 *
 * @property refreshUrl The HTTP/HTTPS URL to call to obtain a new token.
 * @property refreshMethod HTTP method for the refresh call. Default `"POST"`.
 * @property refreshBody Optional raw request body sent to [refreshUrl] (e.g. a JSON
 *   payload containing a refresh token). Sent with `Content-Type: application/json` when
 *   non-null.
 * @property refreshHeaders Optional headers sent with the refresh request (e.g. a client
 *   ID/secret pair, or a refresh-token cookie).
 * @property tokenResponsePath Dot-notation path into the refresh response's JSON body
 *   locating the new token, e.g. `"access_token"` or `"auth.access_token"`. A response
 *   whose body isn't valid JSON, that doesn't have a JSON primitive value (string or
 *   number) at this path, or whose value there is blank, is treated as a failed refresh
 *   (original 401 is returned, not retried).
 * @property authHeaderName Header the new token is injected into on retry. Default
 *   `"Authorization"`.
 * @property authHeaderPrefix Prefix prepended to the token before it's placed in
 *   [authHeaderName]. Default `"Bearer "` (note the trailing space).
 */
@Serializable
data class TokenRefreshConfig(
    val refreshUrl: String,
    val refreshMethod: String = "POST",
    val refreshBody: String? = null,
    val refreshHeaders: Map<String, String>? = null,
    val tokenResponsePath: String = "access_token",
    val authHeaderName: String = "Authorization",
    val authHeaderPrefix: String = "Bearer "
) {
    init {
        require(refreshUrl.startsWith("http://") || refreshUrl.startsWith("https://")) {
            "refreshUrl must start with http:// or https://"
        }
        require(refreshMethod.isNotBlank()) {
            "refreshMethod must not be blank"
        }
        require(tokenResponsePath.isNotBlank()) {
            "tokenResponsePath must not be blank"
        }
        require(authHeaderName.isNotBlank()) {
            "authHeaderName must not be blank"
        }
    }
}
