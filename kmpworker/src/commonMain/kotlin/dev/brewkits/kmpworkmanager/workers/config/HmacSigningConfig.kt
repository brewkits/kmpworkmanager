package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * HMAC-SHA256 request signing, applied by [dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker]
 * before a request is sent. Mirrors the Flutter `native_workmanager` `request_signing.dart`
 * config so a payload shared between the two scheduling layers parses identically.
 *
 * The canonical string signed is:
 * ```
 * METHOD\nURL\nBODY\nTIMESTAMP
 * ```
 * where `BODY` is empty when [signBody] is `false`, and `TIMESTAMP` is empty when
 * [includeTimestamp] is `false`. The signature is HMAC-SHA256([secretKey], canonical),
 * hex-encoded, and sent in the [headerName] header (optionally prefixed by
 * [signaturePrefix] — e.g. `"sha256="` for GitHub-webhook-style signatures).
 *
 * **Security note:** [secretKey] is persisted as part of the worker's task input (same as
 * any `Authorization` header value passed via `HttpRequestConfig.headers` today) so it
 * survives process death for retries. Do not use a key that must never touch disk.
 *
 * @property secretKey The HMAC signing key. Minimum 16 characters — a shorter key is
 *   rejected at construction time rather than producing a weak, easily brute-forced signature.
 * @property headerName Header the computed signature is sent in. Default `"X-Signature"`.
 * @property signaturePrefix Optional prefix prepended to the hex signature before it's
 *   placed in [headerName] — e.g. `"sha256="` to match GitHub webhook signature format.
 *   `null` (default) sends the raw hex digest with no prefix.
 * @property signBody When `true` (default), the request body is included in the canonical
 *   string. Set `false` for GET/DELETE requests with no body, or when the body is streamed
 *   and unavailable at signing time.
 * @property includeTimestamp When `true` (default), a millisecond epoch timestamp is
 *   included in the canonical string and also sent in [timestampHeaderName], letting the
 *   receiving server reject stale/replayed requests. Set `false` for servers whose
 *   canonical format has no timestamp component.
 * @property timestampHeaderName Header the timestamp is sent in when [includeTimestamp] is
 *   `true`. Default `"X-Timestamp"`.
 */
@Serializable
data class HmacSigningConfig(
    val secretKey: String,
    val headerName: String = "X-Signature",
    val signaturePrefix: String? = null,
    val signBody: Boolean = true,
    val includeTimestamp: Boolean = true,
    val timestampHeaderName: String = "X-Timestamp"
) {
    init {
        require(secretKey.length >= 16) {
            "secretKey must be at least 16 characters, got ${secretKey.length}"
        }
        require(headerName.isNotBlank()) {
            "headerName must not be blank"
        }
        require(timestampHeaderName.isNotBlank()) {
            "timestampHeaderName must not be blank"
        }
    }
}
