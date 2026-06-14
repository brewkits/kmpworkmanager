package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.workers.config.ChecksumAlgorithm
import dev.brewkits.kmpworkmanager.workers.config.DuplicatePolicy
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.IOException
import okio.Sink
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlinx.coroutines.CancellationException

/**
 * Built-in worker for downloading files from HTTP/HTTPS URLs.
 *
 * **Resumable downloads (v2.5+).** When [HttpDownloadConfig.resumable] is `true` (default),
 * the worker keeps the in-progress download in `<savePath>.partial` across attempts. The
 * next attempt sends `Range: bytes=N-` (where `N` is the size of the existing partial file)
 * to resume from where the previous attempt died. This is the camera-app feature-killer
 * for large media uploads — a process kill or network blip mid-stream no longer restarts
 * from byte 0.
 *
 * Server contract:
 * - `206 Partial Content` with `Content-Range: bytes N-M/Total` — partial file is appended.
 * - `200 OK` (server ignored or doesn't support `Range`) — partial file is overwritten.
 * - `416 Range Not Satisfiable` — the existing partial is considered already-complete and
 *   atomically moved to `savePath`.
 *
 * If your endpoint cannot tolerate `Range` headers at all, set `resumable = false`.
 */
class HttpDownloadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        // Parse + validate FIRST. Programming errors (bad JSON, bad URL, bad save path)
        // are permanent — they must surface as `Failure`, never `Retry`, otherwise the
        // scheduler will burn battery looping on input that will never become valid.
        val config = try {
            HttpWorkerJson.decodeFromString<HttpDownloadConfig>(input)
        } catch (e: kotlinx.serialization.SerializationException) {
            return WorkerResult.Failure("Invalid HttpDownloadConfig JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WorkerResult.Failure("Invalid HttpDownloadConfig: ${e.message}")
        }

        if (!SecurityValidator.validateURL(config.url)) {
            return WorkerResult.Failure("Invalid or unsafe URL")
        }
        if (!SecurityValidator.validateFilePath(config.savePath)) {
            return WorkerResult.Failure("Invalid or unsafe save path")
        }

        // Everything past this point is the actual network / disk operation. Transient
        // failures here (network drop, disk I/O blip) get the `Retry` treatment — the
        // partial file is preserved when `resumable=true` so the next attempt resumes.
        return try {
            downloadFile(httpClient, config, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("HttpDownloadWorker", "Download failed", e)
            WorkerResult.Retry(
                reason = "download failed: ${e.message ?: e::class.simpleName ?: "unknown"}",
                delayMs = 15_000L
            )
        }
    }

    private suspend fun downloadFile(
        client: HttpClient,
        config: HttpDownloadConfig,
        env: WorkerEnvironment
    ): WorkerResult {
        val originalSavePath = config.savePath.toPath()

        // Ensure parent dir exists. Done early so DuplicatePolicy.RENAME can probe
        // the directory and SKIP can stat without crashing on missing parent.
        originalSavePath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }

        // DuplicatePolicy gating — happens BEFORE any network work. Each branch reasons
        // about a pre-existing file at `originalSavePath`; the chosen behaviour is fully
        // observable through the final `savePath` used below.
        val savePath: Path = when (config.onDuplicate) {
            DuplicatePolicy.OVERWRITE -> originalSavePath
            DuplicatePolicy.SKIP -> {
                if (fileSystem.exists(originalSavePath)) {
                    val size = fileSystem.metadata(originalSavePath).size ?: 0L
                    Logger.i(
                        "HttpDownloadWorker",
                        "DuplicatePolicy.SKIP — file already present at ${originalSavePath} " +
                            "(${SecurityValidator.formatByteSize(size)}). Returning Success without network call."
                    )
                    return WorkerResult.Success(
                        message = "Skipped — existing file at ${config.savePath} " +
                            "(${SecurityValidator.formatByteSize(size)})"
                    )
                }
                originalSavePath
            }
            DuplicatePolicy.RENAME -> renameIfExists(originalSavePath).also { resolved ->
                if (resolved != originalSavePath) {
                    Logger.i(
                        "HttpDownloadWorker",
                        "DuplicatePolicy.RENAME — saving to ${resolved} (original existed)"
                    )
                }
            }
        }
        val partialPath: Path = "${savePath}.partial".toPath()

        // Existing partial → resume offset.
        val existingBytes: Long = if (config.resumable && fileSystem.exists(partialPath)) {
            fileSystem.metadata(partialPath).size ?: 0L
        } else {
            // Non-resumable mode: always start fresh. Delete any stale partial defensively.
            if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
            0L
        }

        val effectiveMaxBytes = (config.maxBytes ?: SecurityValidator.MAX_RESPONSE_BODY_SIZE.toLong())
            .coerceAtMost(SecurityValidator.MAX_RESPONSE_BODY_SIZE.toLong())

        if (existingBytes >= effectiveMaxBytes) {
            // Partial already exceeded the configured ceiling — refuse to continue and
            // remove the partial so a subsequent run can start over.
            fileSystem.delete(partialPath)
            return WorkerResult.Failure(
                "Existing partial (${SecurityValidator.formatByteSize(existingBytes)}) " +
                    "exceeds maxBytes (${SecurityValidator.formatByteSize(effectiveMaxBytes)})"
            )
        }

        return try {
            val response: HttpResponse = client.get(config.url) {
                timeout {
                    requestTimeoutMillis = config.timeoutMs
                    connectTimeoutMillis = config.timeoutMs
                    socketTimeoutMillis = config.timeoutMs
                }
                SecurityValidator.sanitizeHeaders(config.headers)?.forEach { (key, value) -> header(key, value) }
                if (config.resumable && existingBytes > 0L) {
                    header(HttpHeaders.Range, "bytes=$existingBytes-")
                    Logger.i(
                        "HttpDownloadWorker",
                        "Resuming download from ${SecurityValidator.formatByteSize(existingBytes)}: ${config.url}"
                    )
                }
            }

            when (response.status.value) {
                // 416 Range Not Satisfiable — server says the partial is already complete
                // (or beyond content length). Treat the partial as the final file.
                416 -> {
                    if (existingBytes > 0L) {
                        finalizePartial(partialPath, savePath)
                        return WorkerResult.Success(
                            "Resumed download already complete (${SecurityValidator.formatByteSize(existingBytes)})"
                        )
                    }
                    return WorkerResult.Failure("HTTP 416 with no existing partial")
                }
                // 4xx (except 416) — caller bug or auth issue, do not retry.
                in 400..499 -> return WorkerResult.Failure("HTTP ${response.status.value} error")
                in 500..599 -> return WorkerResult.Retry(
                    reason = "server returned HTTP ${response.status.value}",
                    delayMs = 30_000L
                )
            }
            if (!response.status.isSuccess()) {
                return WorkerResult.Failure("HTTP ${response.status.value} error")
            }

            // Server honored our Range request iff it returned 206. Otherwise (200) the partial
            // we have on disk is stale — overwrite from the beginning.
            val partialContent = response.status == HttpStatusCode.PartialContent
            val appendMode = config.resumable && existingBytes > 0L && partialContent

            if (config.resumable && existingBytes > 0L && !partialContent) {
                Logger.w(
                    "HttpDownloadWorker",
                    "Server ignored Range header (status=${response.status.value}) — restarting download from byte 0."
                )
                // Drop the stale partial so the truncating write below starts fresh.
                if (fileSystem.exists(partialPath)) fileSystem.delete(partialPath)
            }

            val totalLengthFromHeader = parseTotalLength(response, existingBytes, appendMode)
            var downloadedThisAttempt = 0L
            val startingOffset = if (appendMode) existingBytes else 0L
            var lastReportTime = 0L
            val reportIntervalMs = 200L

            withContext(AppDispatchers.IO) {
                val sink: Sink = if (appendMode) {
                    fileSystem.appendingSink(partialPath)
                } else {
                    fileSystem.sink(partialPath)
                }
                sink.use { rawSink: Sink ->
                    val buffered = rawSink.buffer()
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)

                    while (!channel.isClosedForRead) {
                        if (env.isCancelled()) throw CancellationException("Download cancelled", null)

                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            buffered.write(buffer, 0, bytesRead)
                            downloadedThisAttempt += bytesRead

                            val totalOnDisk = startingOffset + downloadedThisAttempt
                            if (totalOnDisk > effectiveMaxBytes) {
                                throw IOException(
                                    "Response too large: ${SecurityValidator.formatByteSize(totalOnDisk)} " +
                                        "exceeds limit of ${SecurityValidator.formatByteSize(effectiveMaxBytes)}"
                                )
                            }

                            if (totalLengthFromHeader > 0L) {
                                val now = currentTimeMillis()
                                if (now - lastReportTime >= reportIntervalMs || totalOnDisk == totalLengthFromHeader) {
                                    lastReportTime = now
                                    val pct = ((totalOnDisk * 100) / totalLengthFromHeader).toInt().coerceIn(0, 100)
                                    env.progressListener?.onProgressUpdate(
                                        WorkerProgress(
                                            pct,
                                            "Downloaded ${SecurityValidator.formatByteSize(totalOnDisk)}"
                                        )
                                    )
                                }
                            }
                        }
                    }
                    buffered.flush()
                }
            }

            // Checksum verification — happens BEFORE finalize so a mismatched partial
            // never appears at the user-visible `savePath`. The partial is deleted on
            // mismatch; we return Failure (not Retry) because the bytes on disk are
            // demonstrably wrong, retrying the same URL is likely to reproduce the
            // problem (e.g. a stale CDN cache pinning the corrupted version).
            val expected = config.expectedChecksum
            if (expected != null) {
                val actual = computeChecksum(partialPath, config.checksumAlgorithm)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Logger.e(
                        "HttpDownloadWorker",
                        "Checksum mismatch (${config.checksumAlgorithm}): expected=$expected actual=$actual " +
                            "— deleting partial at $partialPath"
                    )
                    try {
                        fileSystem.delete(partialPath)
                    } catch (e: Exception) {
                        Logger.w("HttpDownloadWorker", "Failed to delete partial after checksum mismatch: $partialPath", e)
                    }
                    return WorkerResult.Failure(
                        "${config.checksumAlgorithm} mismatch — expected $expected, got $actual"
                    )
                }
                Logger.i(
                    "HttpDownloadWorker",
                    "Checksum OK (${config.checksumAlgorithm}): $actual"
                )
            }

            finalizePartial(partialPath, savePath)

            val totalBytes = startingOffset + downloadedThisAttempt
            WorkerResult.Success(
                message = "Downloaded ${SecurityValidator.formatByteSize(totalBytes)}" +
                    if (appendMode) " (resumed from ${SecurityValidator.formatByteSize(startingOffset)})" else "" +
                    if (savePath != originalSavePath) " (renamed to ${savePath.name})" else ""
            )
        } catch (e: CancellationException) {
            // Preserve the partial so the next attempt resumes.
            throw e
        } catch (e: Exception) {
            // Preserve the partial when resumable; otherwise clean up so the next attempt
            // does not see a stale half-written file.
            if (!config.resumable && fileSystem.exists(partialPath)) {
                try {
                    fileSystem.delete(partialPath)
                } catch (cleanup: Exception) {
                    Logger.w("HttpDownloadWorker", "Failed to delete partial on error: $partialPath", cleanup)
                }
            }
            throw e
        }
    }

    private suspend fun finalizePartial(partialPath: Path, savePath: Path) {
        withContext(AppDispatchers.IO) {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            fileSystem.atomicMove(partialPath, savePath)
        }
    }

    /**
     * Pull the total length from `Content-Range` when present (preferred — accurate for
     * resumed downloads). Falls back to `Content-Length`, which for a 206 response only
     * reflects the remaining bytes; combine it with [existingBytes] in that case to keep
     * progress percentage stable.
     */
    private fun parseTotalLength(response: HttpResponse, existingBytes: Long, appendMode: Boolean): Long {
        val contentRange = response.headers[HttpHeaders.ContentRange]
        if (contentRange != null) {
            // Format: "bytes 0-499/1234" or "bytes 0-499/*"
            val slash = contentRange.lastIndexOf('/')
            if (slash >= 0) {
                val totalToken = contentRange.substring(slash + 1)
                val total = totalToken.toLongOrNull()
                if (total != null && total > 0) return total
            }
        }
        val contentLength = response.contentLength() ?: -1L
        if (contentLength <= 0) return -1L
        return if (appendMode) existingBytes + contentLength else contentLength
    }

    /**
     * Stream the partial file through a [HashingSource] and return the hex digest.
     *
     * Uses Okio's platform-native digest implementations:
     *   - Android / JVM: `java.security.MessageDigest`
     *   - iOS / K-Native: `CommonCrypto` (Apple) under the hood.
     * Bounded RAM regardless of file size — we read into a fixed 8 KiB sink, the
     * `HashingSource` updates state as bytes flow through.
     */
    private suspend fun computeChecksum(path: Path, algorithm: ChecksumAlgorithm): String =
        withContext(AppDispatchers.IO) {
            val source = fileSystem.source(path)
            val hashing = when (algorithm) {
                ChecksumAlgorithm.MD5 -> HashingSource.md5(source)
                ChecksumAlgorithm.SHA1 -> HashingSource.sha1(source)
                ChecksumAlgorithm.SHA256 -> HashingSource.sha256(source)
                ChecksumAlgorithm.SHA512 -> HashingSource.sha512(source)
            }
            hashing.use { hs ->
                // blackholeSink discards bytes; HashingSource updates digest as bytes
                // are pulled through. Buffer ensures we use Okio's efficient transfer.
                hs.buffer().use { it.readAll(blackholeSink()) }
                hs.hash.hex()
            }
        }

    /**
     * Resolve a non-existing path by appending `_1`, `_2`, … before the file extension.
     * `photo.jpg` → `photo.jpg` if free, else `photo_1.jpg`, `photo_2.jpg`, … until a
     * gap is found.
     *
     * Bounded at 10 000 attempts so a directory full of `photo_*.jpg` (e.g. user has
     * 10 000 already) cannot hang the worker; if the cap is reached we return the last
     * tried path and let the download overwrite — better than infinite-looping.
     */
    private fun renameIfExists(path: Path): Path {
        if (!fileSystem.exists(path)) return path
        val name = path.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val parent = path.parent ?: return path
        var i = 1
        while (i <= 10_000) {
            val candidate = parent.resolve("${stem}_$i$ext")
            if (!fileSystem.exists(candidate)) return candidate
            i++
        }
        Logger.w(
            "HttpDownloadWorker",
            "DuplicatePolicy.RENAME exhausted 10000 suffixes for ${path.name} — falling back to overwrite"
        )
        return path
    }
}
