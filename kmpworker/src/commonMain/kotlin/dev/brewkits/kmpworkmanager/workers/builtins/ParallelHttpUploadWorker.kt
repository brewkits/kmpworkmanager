package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import dev.brewkits.kmpworkmanager.workers.config.ParallelHttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.config.ParallelUploadFile
import dev.brewkits.kmpworkmanager.workers.config.ParallelUploadFileResult
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Built-in worker that uploads multiple files concurrently — one HTTP request per file.
 *
 * Concurrency is bounded by [ParallelHttpUploadConfig.maxConcurrent]; per-file retry on
 * 5xx/network failure is bounded by [ParallelHttpUploadConfig.maxRetries] with a fixed
 * 2s backoff. 4xx responses are NOT retried (treated as caller-side errors).
 *
 * **Returns `Success`** when every file ends in `success = true`. Otherwise returns
 * `Failure` with the per-file outcomes encoded into the failure message; the structured
 * results are always emitted via `Success.data.fileResults` for the success path.
 *
 * **Progress** is the aggregate "files completed / total files" counter — byte-level
 * progress per file is not aggregated to avoid log spam when N files upload at once.
 */
class ParallelHttpUploadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        val config = try {
            KmpWorkManagerRuntime.json.decodeFromString<ParallelHttpUploadConfig>(input)
        } catch (e: kotlinx.serialization.SerializationException) {
            return WorkerResult.Failure("Invalid ParallelHttpUploadConfig JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WorkerResult.Failure("Invalid ParallelHttpUploadConfig: ${e.message}")
        }

        if (!SecurityValidator.validateURL(config.url)) {
            return WorkerResult.Failure("Invalid or unsafe URL")
        }
        for (f in config.files) {
            if (!SecurityValidator.validateFilePath(f.filePath)) {
                return WorkerResult.Failure("Invalid or unsafe file path: ${f.filePath}")
            }
        }

        return try {
            doUpload(config, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("ParallelHttpUploadWorker", "Upload batch failed", e)
            WorkerResult.Retry(
                reason = "upload batch failed: ${e.message ?: e::class.simpleName ?: "unknown"}",
                delayMs = 15_000L
            )
        }
    }

    private suspend fun doUpload(
        config: ParallelHttpUploadConfig,
        env: WorkerEnvironment
    ): WorkerResult {
        val total = config.files.size
        val completed = atomic(0)
        val sem = Semaphore(permits = config.maxConcurrent)

        val results: List<ParallelUploadFileResult> = coroutineScope {
            config.files.map { file ->
                async(AppDispatchers.IO) {
                    sem.withPermit {
                        if (env.isCancelled()) throw CancellationException("Parallel upload cancelled", null)
                        val outcome = uploadOneFileWithRetry(config, file)
                        val done = completed.incrementAndGet()
                        env.progressListener?.onProgressUpdate(
                            WorkerProgress(
                                (done * 100 / total),
                                "Uploaded $done/$total files"
                            )
                        )
                        outcome
                    }
                }
            }.awaitAll()
        }

        val successCount = results.count { it.success }
        val failedCount = total - successCount
        val totalBytes = results.sumOf { it.bytesSent }

        val resultsArray: JsonArray = buildJsonObject {
            put("uploadedCount", successCount)
            put("failedCount", failedCount)
            put("totalBytes", totalBytes)
        }.let { _ ->
            // Build fileResults array separately so each entry is a JsonObject.
            kotlinx.serialization.json.buildJsonArray {
                results.forEach { r ->
                    addJsonObject {
                        put("filePath", r.filePath)
                        put("success", r.success)
                        put("attempts", r.attempts)
                        put("bytesSent", r.bytesSent)
                        r.statusCode?.let { put("statusCode", it) }
                        r.error?.let { put("error", it) }
                    }
                }
            }
        }
        val data = buildJsonObject {
            put("uploadedCount", successCount)
            put("failedCount", failedCount)
            put("totalBytes", totalBytes)
            put("fileResults", resultsArray)
        }

        return if (failedCount == 0) {
            WorkerResult.Success(
                message = "Uploaded $successCount/$total files (${SecurityValidator.formatByteSize(totalBytes)})",
                data = data
            )
        } else {
            // Partial failure surfaces as Failure — the chain step did not fully succeed.
            // Per-file outcomes are encoded in the message; a caller that wants structured
            // data should listen to TaskCompletionEvent rather than parse the message.
            //
            // shouldRetry = true: per-file uploads ran out of bounded retries (handled in
            // the loop above), but the chain-level retry budget should still apply — a
            // partial-failure batch usually succeeds on the next BGTask invocation once
            // the network recovers. Pre-v2.5 chain semantics ignored shouldRetry; v2.5
            // treats shouldRetry=false as immediate-abandon, so this must be explicit.
            val firstError = results.firstOrNull { !it.success }?.error ?: "unknown"
            WorkerResult.Failure(
                "Uploaded $successCount/$total files, $failedCount failed " +
                    "(first error: $firstError)",
                shouldRetry = true
            )
        }
    }

    /**
     * Upload one file with bounded retry. Returns a [ParallelUploadFileResult] with the
     * final outcome — never throws into the caller (we want sibling uploads to continue
     * regardless of per-file failure).
     */
    private suspend fun uploadOneFileWithRetry(
        config: ParallelHttpUploadConfig,
        file: ParallelUploadFile
    ): ParallelUploadFileResult {
        var attempt = 0
        var lastError: String? = null
        var lastStatus: Int? = null
        var bytesSent = 0L

        // Total attempts = 1 + maxRetries (the original + each retry).
        val maxAttempts = 1 + config.maxRetries
        while (attempt < maxAttempts) {
            attempt++
            try {
                val (status, bytes) = uploadOne(config, file)
                bytesSent = bytes
                lastStatus = status
                when {
                    status in 200..299 -> {
                        return ParallelUploadFileResult(
                            filePath = file.filePath,
                            success = true,
                            statusCode = status,
                            attempts = attempt,
                            bytesSent = bytes
                        )
                    }
                    status in 400..499 -> {
                        // 4xx — caller bug or auth, do not retry.
                        return ParallelUploadFileResult(
                            filePath = file.filePath,
                            success = false,
                            statusCode = status,
                            attempts = attempt,
                            bytesSent = bytes,
                            error = "HTTP $status"
                        )
                    }
                    else -> {
                        // 5xx / other → retry path.
                        lastError = "HTTP $status"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message}"
                Logger.w(
                    "ParallelHttpUploadWorker",
                    "Upload attempt $attempt/$maxAttempts failed for ${file.filePath}: $lastError"
                )
            }
            if (attempt < maxAttempts) {
                // Fixed 2s backoff between attempts — keeps the worker simple. Callers
                // that want exponential backoff should drop maxRetries to 0 and re-enqueue
                // the failed files themselves with a custom Retry result.
                delay(2_000L)
            }
        }
        return ParallelUploadFileResult(
            filePath = file.filePath,
            success = false,
            statusCode = lastStatus,
            attempts = attempt,
            bytesSent = bytesSent,
            error = lastError ?: "unknown"
        )
    }

    /**
     * Issue one multipart upload. Returns `(httpStatus, bytesSent)`. Throws on network
     * failure (caller retries).
     *
     * Body is streamed from disk via `WriteChannelContent` — peak RAM stays at chunk
     * buffer (64 KiB) regardless of file size, just like [HttpUploadWorker].
     */
    private suspend fun uploadOne(
        config: ParallelHttpUploadConfig,
        file: ParallelUploadFile
    ): Pair<Int, Long> {
        val filePath = file.filePath.toPath()
        val metadata = fileSystem.metadata(filePath)
        val fileSize = metadata.size ?: throw IllegalStateException(
            "Size unknown for ${file.filePath} — refusing to upload (cannot enforce size limit)"
        )
        if (fileSize > SecurityValidator.MAX_REQUEST_BODY_SIZE) {
            throw IllegalStateException(
                "File too large: ${SecurityValidator.formatByteSize(fileSize)} exceeds " +
                    "limit of ${SecurityValidator.formatByteSize(SecurityValidator.MAX_REQUEST_BODY_SIZE.toLong())}"
            )
        }
        val boundary = "KmpWorkManagerBoundary${Random.nextInt(1_000_000)}"
        val fileName = file.fileName ?: filePath.name
        val mimeType = file.mimeType ?: "application/octet-stream"

        var bytesSent = 0L

        val response = httpClient.post(config.url) {
            timeout {
                requestTimeoutMillis = config.timeoutMs
                connectTimeoutMillis = config.timeoutMs
                socketTimeoutMillis = config.timeoutMs
            }
            headers {
                SecurityValidator.sanitizeHeaders(config.headers)?.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType =
                    ContentType.MultiPart.FormData.withParameter("boundary", boundary)

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    config.fields?.forEach { (name, value) ->
                        channel.writeStringUtf8("--$boundary\r\n")
                        channel.writeStringUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        channel.writeStringUtf8("$value\r\n")
                    }
                    channel.writeStringUtf8("--$boundary\r\n")
                    channel.writeStringUtf8(
                        "Content-Disposition: form-data; name=\"${file.fieldName}\"; filename=\"$fileName\"\r\n"
                    )
                    channel.writeStringUtf8("Content-Type: $mimeType\r\n\r\n")

                    withContext(AppDispatchers.IO) {
                        fileSystem.source(filePath).buffer().use { source ->
                            val chunk = Buffer()
                            while (true) {
                                val read = source.read(chunk, 65_536L)
                                if (read <= 0L) break
                                val bytes = chunk.readByteArray()
                                channel.writeFully(bytes)
                                bytesSent += read
                            }
                        }
                    }
                    channel.writeStringUtf8("\r\n--$boundary--\r\n")
                }
            })
        }
        return response.status.value to bytesSent
    }
}
