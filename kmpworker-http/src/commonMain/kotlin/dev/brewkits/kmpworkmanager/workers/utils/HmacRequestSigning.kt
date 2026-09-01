package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import dev.brewkits.kmpworkmanager.workers.config.HmacSigningConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import okio.ByteString.Companion.encodeUtf8

/**
 * Computes the HMAC-SHA256 signature for the canonical string `METHOD\nURL\nBODY\nTIMESTAMP`.
 *
 * Pure function — no I/O, no Ktor dependency beyond the config type — so it's testable
 * without a live or mocked HTTP client. [HmacSigningConfig.signBody]/[HmacSigningConfig.includeTimestamp]
 * control whether [body]/[timestamp] contribute to the canonical string (an omitted
 * component is an empty line, not a removed one, so the line count — and therefore the
 * signature — always reflects which components are enabled).
 *
 * Uses Okio's built-in `ByteString.hmacSha256` (no platform-specific crypto needed —
 * Okio implements HMAC-SHA256 natively on every KMP target this library supports).
 *
 * @return the hex-encoded signature, prefixed with [HmacSigningConfig.signaturePrefix] if set.
 */
internal fun computeHmacSignature(
    method: String,
    url: String,
    body: String?,
    timestamp: String?,
    config: HmacSigningConfig
): String {
    val canonical = buildString {
        append(method.uppercase())
        append('\n')
        append(url)
        append('\n')
        append(if (config.signBody) body.orEmpty() else "")
        append('\n')
        append(timestamp.orEmpty())
    }
    val digest = canonical.encodeUtf8()
        .hmacSha256(config.secretKey.encodeUtf8())
        .hex()
    return config.signaturePrefix?.let { it + digest } ?: digest
}

/**
 * Applies [HmacSigningConfig] to this request builder: computes the signature (and, when
 * [HmacSigningConfig.includeTimestamp] is set, the timestamp used in it) and adds the
 * corresponding header(s). Call this **after** every other header/body mutation on
 * [this] builder — the signature must cover the final outgoing request, not an
 * intermediate state.
 */
internal fun HttpRequestBuilder.applyHmacSigning(
    method: String,
    url: String,
    body: String?,
    config: HmacSigningConfig
) {
    val timestamp = if (config.includeTimestamp) currentTimeMillis().toString() else null
    val signature = computeHmacSignature(method, url, body, timestamp, config)
    header(config.headerName, signature)
    if (timestamp != null) {
        header(config.timestampHeaderName, timestamp)
    }
}
