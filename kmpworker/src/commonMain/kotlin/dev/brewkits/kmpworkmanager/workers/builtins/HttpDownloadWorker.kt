package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.ProgressListener
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import okio.IOException
import kotlinx.coroutines.CancellationException

/**
 * Built-in worker for downloading files from HTTP/HTTPS URLs.
 */
class HttpDownloadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        return try {
            val config = KmpWorkManagerRuntime.json.decodeFromString<HttpDownloadConfig>(input)

            if (!SecurityValidator.validateURL(config.url)) {
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            downloadFile(httpClient, config, env)
        } catch (e: Exception) {
            Logger.e("HttpDownloadWorker", "Download failed", e)
            WorkerResult.Failure("Download failed: ${e.message}")
        }
    }

    private suspend fun downloadFile(client: HttpClient, config: HttpDownloadConfig, env: WorkerEnvironment): WorkerResult {
        val savePath = config.savePath.toPath()
        val tempPath = "${config.savePath}.tmp".toPath()

        return try {
            savePath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }

            val response: HttpResponse = client.get(config.url) {
                config.headers?.forEach { (key, value) -> header(key, value) }
            }

            if (!response.status.isSuccess()) {
                return WorkerResult.Failure("HTTP ${response.status.value} error")
            }

            val contentLength = response.contentLength() ?: -1L
            var downloadedBytes = 0L
            var lastReportTime = 0L
            val reportIntervalMs = 200L

            withContext(AppDispatchers.IO) {
                fileSystem.write(tempPath) {
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)

                    while (!channel.isClosedForRead) {
                        if (env.isCancelled()) throw CancellationException("Download cancelled", null)

                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // Enforce response size limit to prevent disk exhaustion.
                            // SecurityValidator.MAX_RESPONSE_BODY_SIZE was defined but never checked.
                            if (downloadedBytes > SecurityValidator.MAX_RESPONSE_BODY_SIZE) {
                                throw IOException(
                                    "Response too large: ${SecurityValidator.formatByteSize(downloadedBytes)} " +
                                    "exceeds limit of ${SecurityValidator.formatByteSize(SecurityValidator.MAX_RESPONSE_BODY_SIZE.toLong())}"
                                )
                            }

                            if (contentLength > 0) {
                                val now = currentTimeMillis()
                                if (now - lastReportTime >= reportIntervalMs || downloadedBytes == contentLength) {
                                    lastReportTime = now
                                    val progress = ((downloadedBytes * 100) / contentLength).toInt()
                                    env.progressListener?.onProgressUpdate(
                                        WorkerProgress(progress, "Downloaded ${SecurityValidator.formatByteSize(downloadedBytes)}")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            withContext(AppDispatchers.IO) {
                if (fileSystem.exists(savePath)) fileSystem.delete(savePath)
                fileSystem.atomicMove(tempPath, savePath)
            }

            WorkerResult.Success("Downloaded ${SecurityValidator.formatByteSize(downloadedBytes)}")
        } catch (e: Exception) {
            if (fileSystem.exists(tempPath)) fileSystem.delete(tempPath)
            throw e
        }
    }
}
