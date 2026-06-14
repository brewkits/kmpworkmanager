package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import dev.brewkits.kmpworkmanager.workers.config.ChecksumAlgorithm
import dev.brewkits.kmpworkmanager.workers.config.ParallelHttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.atomic
import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * Built-in worker that downloads a single file as N concurrent HTTP byte-range chunks.
 *
 * **Concurrency:** [ParallelHttpDownloadConfig.numChunks] requests run in parallel via
 * a [Semaphore] so we never exceed the configured chunk count even if the server stalls
 * one request and the others race ahead.
 *
 * **Resume:** each chunk is persisted as `<savePath>.partN`. If a previous attempt left
 * some `.partN` files on disk, this run reuses them: chunk N is skipped when the partial
 * is already exactly the size of the byte range. Half-written `.partN` files are
 * truncated and re-downloaded — partial-byte-range append is not safe because we cannot
 * verify where the previous attempt left off without re-fetching headers per chunk.
 *
 * **Fallback:** if the server returns no `Content-Length` on the probe `HEAD` request or
 * does not advertise `Accept-Ranges: bytes`, the worker degrades silently to a single
 * sequential download (identical to [HttpDownloadWorker] with `resumable = false`).
 *
 * **Checksum:** verified against the **merged** file before it lands at `savePath`. A
 * mismatch deletes both the merged file and the `.partN` parts so the next attempt
 * starts clean.
 */
class ParallelHttpDownloadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        // Programming errors (bad JSON, bad URL) → Failure. Transient errors → Retry.
        val config = try {
            HttpWorkerJson.decodeFromString<ParallelHttpDownloadConfig>(input)
        } catch (e: kotlinx.serialization.SerializationException) {
            return WorkerResult.Failure("Invalid ParallelHttpDownloadConfig JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WorkerResult.Failure("Invalid ParallelHttpDownloadConfig: ${e.message}")
        }

        if (!SecurityValidator.validateURL(config.url)) {
            return WorkerResult.Failure("Invalid or unsafe URL")
        }
        if (!SecurityValidator.validateFilePath(config.savePath)) {
            return WorkerResult.Failure("Invalid or unsafe save path")
        }

        return try {
            doDownload(config, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("ParallelHttpDownloadWorker", "Download failed", e)
            // attemptCap=4 guards against a degenerate retry storm — e.g. the merge step
            // hitting disk-full on every attempt. Without a cap, the chain could re-enter
            // here forever (caught in QA review double-check). 4 attempts × 15 s delay =
            // ~1 min of recovery time, enough for transient disk/network issues to clear.
            WorkerResult.Retry(
                reason = "download failed: ${e.message ?: e::class.simpleName ?: "unknown"}",
                delayMs = 15_000L,
                attemptCap = 4,
            )
        }
    }

    private suspend fun doDownload(
        config: ParallelHttpDownloadConfig,
        env: WorkerEnvironment
    ): WorkerResult {
        val savePath = config.savePath.toPath()
        savePath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }

        if (config.skipExisting && fileSystem.exists(savePath)) {
            val size = fileSystem.metadata(savePath).size ?: 0L
            return WorkerResult.Success(
                message = "Skipped — existing file at ${config.savePath} " +
                    "(${SecurityValidator.formatByteSize(size)})"
            )
        }

        // Probe: HEAD request tells us Content-Length + Accept-Ranges. If the server
        // does not support range requests OR refuses HEAD, fall back to sequential.
        val probe = try {
            httpClient.head(config.url) {
                timeout {
                    requestTimeoutMillis = config.timeoutMs
                    connectTimeoutMillis = config.timeoutMs
                    socketTimeoutMillis = config.timeoutMs
                }
                SecurityValidator.sanitizeHeaders(config.headers)
                    ?.forEach { (k, v) -> header(k, v) }
            }
        } catch (e: Exception) {
            Logger.w("ParallelHttpDownloadWorker", "HEAD probe failed (${e.message}) — falling back to sequential")
            null
        }
        val totalBytes = probe?.contentLength() ?: -1L
        val acceptsRanges = probe?.headers?.get(HttpHeaders.AcceptRanges)
            ?.equals("bytes", ignoreCase = true) == true

        if (totalBytes <= 0 || !acceptsRanges || config.numChunks == 1) {
            Logger.i(
                "ParallelHttpDownloadWorker",
                "Falling back to sequential download (contentLength=$totalBytes, " +
                    "acceptsRanges=$acceptsRanges, numChunks=${config.numChunks})"
            )
            return downloadSequential(config, savePath, env)
        }

        Logger.i(
            "ParallelHttpDownloadWorker",
            "Parallel download: ${SecurityValidator.formatByteSize(totalBytes)} → " +
                "${config.numChunks} chunks at ${config.url}"
        )

        val ranges = computeRanges(totalBytes, config.numChunks)
        val partPaths = ranges.indices.map { i -> "${config.savePath}.part$i".toPath() }
        val downloadedAcrossChunks = atomic(0L)
        var lastReportTime = 0L
        val reportIntervalMs = 200L

        val sem = Semaphore(permits = config.numChunks)

        // Launch chunks concurrently — semaphore bounds the in-flight count even if
        // numChunks > permits (1:1 here; defensive against future config changes).
        coroutineScope {
            ranges.mapIndexed { chunkIndex, range ->
                async(AppDispatchers.IO) {
                    sem.withPermit {
                        if (env.isCancelled()) throw CancellationException("Parallel download cancelled", null)
                        downloadOneChunk(
                            config = config,
                            chunkIndex = chunkIndex,
                            range = range,
                            partPath = partPaths[chunkIndex],
                            onBytes = { delta ->
                                val newTotal = downloadedAcrossChunks.addAndGet(delta)
                                // Throttled aggregate progress report. Monotonic time is acceptable
                                // here because we only need rate-limiting, not wall-clock accuracy.
                                val now = dev.brewkits.kmpworkmanager.utils.currentTimeMillis()
                                if (now - lastReportTime >= reportIntervalMs || newTotal == totalBytes) {
                                    lastReportTime = now
                                    val pct = ((newTotal * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    env.progressListener?.onProgressUpdate(
                                        WorkerProgress(
                                            pct,
                                            "Downloaded ${SecurityValidator.formatByteSize(newTotal)} / " +
                                                SecurityValidator.formatByteSize(totalBytes)
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }.awaitAll()
        }

        // Merge .partN files into a single .merged file (NOT directly into savePath —
        // we may still need to verify checksum before user-visible move).
        val mergedPath = "${config.savePath}.merged".toPath()
        try {
            mergeChunks(partPaths, mergedPath)

            // Checksum verification — same logic as HttpDownloadWorker. Mismatch deletes
            // every artifact so the next attempt starts clean.
            val expected = config.expectedChecksum
            if (expected != null) {
                val actual = computeChecksum(mergedPath, config.checksumAlgorithm)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Logger.e(
                        "ParallelHttpDownloadWorker",
                        "Checksum mismatch (${config.checksumAlgorithm}): expected=$expected actual=$actual"
                    )
                    deleteIfExists(mergedPath)
                    partPaths.forEach { deleteIfExists(it) }
                    return WorkerResult.Failure(
                        "${config.checksumAlgorithm} mismatch — expected $expected, got $actual"
                    )
                }
                Logger.i(
                    "ParallelHttpDownloadWorker",
                    "Checksum OK (${config.checksumAlgorithm}): $actual"
                )
            }

            // Final move + cleanup of part files.
            withContext(AppDispatchers.IO) {
                if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
                fileSystem.atomicMove(mergedPath, savePath)
            }
            partPaths.forEach { deleteIfExists(it) }

            return WorkerResult.Success(
                message = "Downloaded ${SecurityValidator.formatByteSize(totalBytes)} " +
                    "in ${config.numChunks} chunks"
            )
        } catch (e: Exception) {
            // Pre-fix behaviour: leave .partN intact, only delete .merged. The QA review
            // showed this caused a retry storm for disk-pressure failures — every retry
            // would see .partN files exist at expected sizes, skip the download, then
            // hit the same disk-full mergeChunks and loop forever (still no progress).
            //
            // Fix: when merge fails (or any post-merge step), purge .partN too. The next
            // retry has to re-download — slower but guarantees forward progress. Combined
            // with the outer `Retry(attemptCap = 4)` in [doWork], a permanently full
            // disk fails the worker cleanly after ~1 minute instead of looping forever.
            Logger.w(
                "ParallelHttpDownloadWorker",
                "Merge/finalize failed (${e::class.simpleName}: ${e.message}). " +
                    "Purging .merged and ${partPaths.size} .partN files to prevent retry storm. " +
                    "Next attempt will re-download from scratch."
            )
            deleteIfExists(mergedPath)
            partPaths.forEach { deleteIfExists(it) }
            throw e
        }
    }

    /**
     * Compute closed-open byte ranges that partition `[0, totalBytes)` into `numChunks`
     * roughly-equal slices. The last chunk absorbs any remainder so the partition is
     * exact.
     */
    private fun computeRanges(totalBytes: Long, numChunks: Int): List<LongRange> {
        val base = totalBytes / numChunks
        val ranges = mutableListOf<LongRange>()
        var start = 0L
        for (i in 0 until numChunks) {
            val end = if (i == numChunks - 1) totalBytes - 1 else start + base - 1
            ranges += start..end
            start = end + 1
        }
        return ranges
    }

    /**
     * Download one chunk's byte range into `partPath`. If `partPath` already exists at
     * exactly the right size, the chunk is skipped (resume case). Otherwise the partial
     * is truncated and refetched from byte 0 of the range.
     */
    private suspend fun downloadOneChunk(
        config: ParallelHttpDownloadConfig,
        chunkIndex: Int,
        range: LongRange,
        partPath: Path,
        onBytes: (Long) -> Unit
    ) {
        val expectedSize = range.last - range.first + 1
        if (fileSystem.exists(partPath)) {
            val existing = fileSystem.metadata(partPath).size ?: 0L
            if (existing == expectedSize) {
                Logger.d(
                    "ParallelHttpDownloadWorker",
                    "Chunk $chunkIndex resume — part file already complete ($existing bytes)"
                )
                // Still notify aggregate progress so the percentage doesn't appear stuck.
                onBytes(existing)
                return
            }
            // Truncate and refetch — see class kdoc for rationale.
            fileSystem.delete(partPath)
        }

        val rangeHeader = "bytes=${range.first}-${range.last}"
        val response: HttpResponse = httpClient.get(config.url) {
            timeout {
                requestTimeoutMillis = config.timeoutMs
                connectTimeoutMillis = config.timeoutMs
                socketTimeoutMillis = config.timeoutMs
            }
            SecurityValidator.sanitizeHeaders(config.headers)
                ?.forEach { (k, v) -> header(k, v) }
            header(HttpHeaders.Range, rangeHeader)
        }

        if (!response.status.isSuccess()) {
            throw IOException(
                "Chunk $chunkIndex HTTP ${response.status.value} for $rangeHeader"
            )
        }

        withContext(AppDispatchers.IO) {
            fileSystem.sink(partPath).use { rawSink: Sink ->
                val buffered = rawSink.buffer()
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buf = ByteArray(8192)
                var written = 0L
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    if (n > 0) {
                        buffered.write(buf, 0, n)
                        written += n
                        onBytes(n.toLong())
                    }
                }
                buffered.flush()
                if (written != expectedSize) {
                    throw IOException(
                        "Chunk $chunkIndex truncated: got $written bytes, expected $expectedSize"
                    )
                }
            }
        }
    }

    /**
     * Concatenate `.partN` files into a single file at `mergedPath`. Buffered I/O so the
     * RAM footprint stays bounded regardless of file size.
     */
    private suspend fun mergeChunks(partPaths: List<Path>, mergedPath: Path) {
        withContext(AppDispatchers.IO) {
            if (fileSystem.exists(mergedPath)) fileSystem.delete(mergedPath)
            fileSystem.sink(mergedPath).buffer().use { out ->
                for (part in partPaths) {
                    fileSystem.source(part).buffer().use { src ->
                        out.writeAll(src)
                    }
                }
                out.flush()
            }
        }
    }

    /**
     * Sequential fallback — used when the server does not support range requests OR the
     * caller asked for `numChunks = 1`. Reuses the `HttpDownloadWorker` semantics
     * inline rather than spawning a second worker (avoids extra config translation +
     * keeps a single source of truth for telemetry of *this* worker class).
     */
    private suspend fun downloadSequential(
        config: ParallelHttpDownloadConfig,
        savePath: Path,
        env: WorkerEnvironment
    ): WorkerResult {
        val tempPath: Path = "${config.savePath}.tmp".toPath()
        val response: HttpResponse = httpClient.get(config.url) {
            timeout {
                requestTimeoutMillis = config.timeoutMs
                connectTimeoutMillis = config.timeoutMs
                socketTimeoutMillis = config.timeoutMs
            }
            SecurityValidator.sanitizeHeaders(config.headers)?.forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            return WorkerResult.Failure("HTTP ${response.status.value} error")
        }

        val totalBytes = response.contentLength() ?: -1L
        var downloaded = 0L
        var lastReport = 0L

        withContext(AppDispatchers.IO) {
            fileSystem.sink(tempPath).use { rawSink: Sink ->
                val buffered = rawSink.buffer()
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buf = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    if (env.isCancelled()) throw CancellationException("Sequential download cancelled", null)
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    if (n > 0) {
                        buffered.write(buf, 0, n)
                        downloaded += n
                        if (totalBytes > 0) {
                            val now = dev.brewkits.kmpworkmanager.utils.currentTimeMillis()
                            if (now - lastReport >= 200L || downloaded == totalBytes) {
                                lastReport = now
                                val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                env.progressListener?.onProgressUpdate(
                                    WorkerProgress(
                                        pct,
                                        "Downloaded ${SecurityValidator.formatByteSize(downloaded)}"
                                    )
                                )
                            }
                        }
                    }
                }
                buffered.flush()
            }
        }

        // Optional checksum on the sequential path too.
        val expected = config.expectedChecksum
        if (expected != null) {
            val actual = computeChecksum(tempPath, config.checksumAlgorithm)
            if (!actual.equals(expected, ignoreCase = true)) {
                deleteIfExists(tempPath)
                return WorkerResult.Failure(
                    "${config.checksumAlgorithm} mismatch — expected $expected, got $actual"
                )
            }
        }

        withContext(AppDispatchers.IO) {
            if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
            fileSystem.atomicMove(tempPath, savePath)
        }
        return WorkerResult.Success(
            message = "Downloaded ${SecurityValidator.formatByteSize(downloaded)} (sequential)"
        )
    }

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
                hs.buffer().use { it.readAll(blackholeSink()) }
                hs.hash.hex()
            }
        }

    private fun deleteIfExists(path: Path) {
        try {
            if (fileSystem.exists(path)) fileSystem.delete(path)
        } catch (e: Exception) {
            Logger.w("ParallelHttpDownloadWorker", "Failed to delete $path: ${e.message}")
        }
    }
}
