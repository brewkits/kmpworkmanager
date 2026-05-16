package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for HttpDownloadWorker.
 *
 * @property url The HTTP/HTTPS URL to download from
 * @property savePath Absolute path where to save the downloaded file
 * @property headers Optional HTTP headers
 * @property timeoutMs Download timeout in milliseconds (default: 300000ms = 5 minutes)
 * @property resumable When `true` (default), the worker persists the partial download to
 *   `<savePath>.partial` and, on the next attempt, issues an HTTP `Range` request to
 *   resume from the byte count already on disk. This is the camera-app feature-killer:
 *   a process kill mid-download no longer restarts from byte 0. The server must honor
 *   `Range` (respond with `206 Partial Content`); if it returns `200 OK` instead, the
 *   worker treats the response as a fresh download and overwrites the partial file.
 *   Set to `false` for endpoints that cannot tolerate `Range` headers.
 * @property maxBytes Optional hard cap on the downloaded size, in bytes. Defaults to
 *   `null` which uses the library default ceiling (`SecurityValidator.MAX_RESPONSE_BODY_SIZE`).
 *   Per-config caps below the library ceiling are honored; above is clamped to the ceiling.
 * @property expectedChecksum Optional hex-encoded checksum the downloaded file must match
 *   after the body is fully received. When present, [checksumAlgorithm] selects the digest.
 *   Mismatched files are deleted and the worker returns `Failure` — this guards against
 *   truncation, transparent middlebox rewriting, and silent CDN cache corruption that
 *   transport-layer integrity checks (TLS, TCP) cannot catch above the protocol layer.
 *   Comparison is case-insensitive.
 * @property checksumAlgorithm Algorithm to use when [expectedChecksum] is set. Ignored
 *   when [expectedChecksum] is `null`. Default: `SHA256`.
 * @property onDuplicate How to handle a pre-existing file at [savePath] before the
 *   download starts. Default `OVERWRITE` preserves pre-v2.5 behaviour. Use `SKIP` for
 *   resumable batch imports, `RENAME` for media-gallery flows.
 */
@Serializable
data class HttpDownloadConfig(
    val url: String,
    val savePath: String,
    val headers: Map<String, String>? = null,
    val timeoutMs: Long = 300000,
    val resumable: Boolean = true,
    val maxBytes: Long? = null,
    val expectedChecksum: String? = null,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    val onDuplicate: DuplicatePolicy = DuplicatePolicy.OVERWRITE
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(savePath.isNotBlank()) {
            "Save path cannot be blank"
        }
        require(timeoutMs > 0) {
            "Timeout must be positive"
        }
        if (maxBytes != null) {
            require(maxBytes > 0) { "maxBytes must be positive when set, got $maxBytes" }
        }
        if (expectedChecksum != null) {
            require(expectedChecksum.isNotBlank()) {
                "expectedChecksum must not be blank — pass null to disable verification"
            }
            // Hex-only sanity: catches Base64 / data-URL mistakes at enqueue time rather
            // than after a 500MB download finishes and the comparison fails.
            require(expectedChecksum.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "expectedChecksum must be hex-encoded (0-9, a-f). Got: '$expectedChecksum'"
            }
        }
    }
}
