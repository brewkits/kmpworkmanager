package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for [ParallelHttpDownloadWorker].
 *
 * Splits a single-file download into [numChunks] concurrent byte-range requests
 * (HTTP/1.1 `Range`, RFC 7233), each saved as `<savePath>.partN`. When all chunks
 * complete the parts are concatenated into the final file. If the server does not
 * advertise `Accept-Ranges: bytes` or returns no `Content-Length`, the worker falls
 * back to a single sequential download.
 *
 * **When to choose this over [HttpDownloadConfig]:** files larger than ~50 MB on a
 * Wi-Fi connection where you have CPU/sockets to spare. Values of [numChunks] above
 * 8 rarely improve throughput because most CDNs throttle per-connection.
 *
 * @property url URL to download.
 * @property savePath Absolute path where the merged file will be saved.
 * @property numChunks Number of parallel byte-range chunks (1..16, default 4). `1` is
 *   equivalent to a sequential download but still flows through the parallel code path
 *   — useful for forcing chunked semantics even when the server has poor parallelism.
 * @property headers Optional headers sent with every chunk request.
 * @property timeoutMs Per-chunk request timeout. Default 10 minutes (each chunk
 *   counts independently).
 * @property expectedChecksum Optional hex digest verified against the **merged** file.
 *   Mismatched merged file is deleted; the worker returns `Failure`.
 * @property checksumAlgorithm Algorithm for [expectedChecksum]. Default `SHA256`.
 * @property skipExisting When `true`, return `Success` immediately if [savePath] already
 *   exists. Cheaper equivalent of [DuplicatePolicy.SKIP] for the parallel worker.
 */
@Serializable
data class ParallelHttpDownloadConfig(
    val url: String,
    val savePath: String,
    val numChunks: Int = 4,
    val headers: Map<String, String>? = null,
    val timeoutMs: Long = 600_000L,
    val expectedChecksum: String? = null,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    val skipExisting: Boolean = false
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(savePath.isNotBlank()) { "Save path cannot be blank" }
        require(numChunks in 1..16) {
            "numChunks must be between 1 and 16 (got $numChunks). " +
                "Values above 8 rarely improve throughput; 16 is a hard cap to keep CDN " +
                "connection budget reasonable."
        }
        require(timeoutMs > 0) { "Timeout must be positive" }
        if (expectedChecksum != null) {
            require(expectedChecksum.isNotBlank()) {
                "expectedChecksum must not be blank — pass null to disable verification"
            }
            require(expectedChecksum.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "expectedChecksum must be hex-encoded (0-9, a-f). Got: '$expectedChecksum'"
            }
        }
    }
}
