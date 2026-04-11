package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.AppDispatchers
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.utils.platformFileSystem
import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Built-in worker for uploading files using multipart/form-data.
 */
class HttpUploadWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance,
    private val fileSystem: FileSystem = platformFileSystem
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        return try {
            val config = KmpWorkManagerRuntime.json.decodeFromString<HttpUploadConfig>(input)

            if (!SecurityValidator.validateURL(config.url)) {
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            uploadFile(httpClient, config, env)
        } catch (e: Exception) {
            Logger.e("HttpUploadWorker", "Upload failed", e)
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }

    private suspend fun uploadFile(client: HttpClient, config: HttpUploadConfig, env: WorkerEnvironment): WorkerResult {
        val filePath = config.filePath.toPath()
        val metadata = fileSystem.metadata(filePath)
        val fileSize = metadata.size ?: return WorkerResult.Failure("Size unknown")

        // Enforce request size limit before starting the upload.
        if (fileSize > SecurityValidator.MAX_REQUEST_BODY_SIZE) {
            return WorkerResult.Failure(
                "File too large: ${SecurityValidator.formatByteSize(fileSize)} exceeds " +
                "limit of ${SecurityValidator.formatByteSize(SecurityValidator.MAX_REQUEST_BODY_SIZE.toLong())}"
            )
        }
        val boundary = "KmpWorkManagerBoundary${Random.nextInt(1000000)}"

        val response = client.post(config.url) {
            headers {
                config.headers?.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            }

            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
                
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    // 1. Form fields
                    config.fields?.forEach { (name, value) ->
                        channel.writeStringUtf8("--$boundary\r\n")
                        channel.writeStringUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        channel.writeStringUtf8("$value\r\n")
                    }

                    // 2. File part
                    channel.writeStringUtf8("--$boundary\r\n")
                    channel.writeStringUtf8("Content-Disposition: form-data; name=\"${config.fileFieldName}\"; filename=\"${filePath.name}\"\r\n")
                    channel.writeStringUtf8("Content-Type: ${config.mimeType ?: "application/octet-stream"}\r\n\r\n")

                    var bytesUploaded = 0L
                    var lastReportTime = 0L
                    val reportIntervalMs = 200L

                    // Okio blocking read must be inside AppDispatchers.IO
                    withContext(AppDispatchers.IO) {
                        fileSystem.source(filePath).buffer().use { source ->
                            val chunkBuf = Buffer()
                            while (true) {
                                if (env.isCancelled()) throw CancellationException("Upload cancelled", null)

                                val read = source.read(chunkBuf, 65536L)
                                if (read <= 0L) break
                                
                                val bytes = chunkBuf.readByteArray()
                                channel.writeFully(bytes)
                                bytesUploaded += read

                                val now = currentTimeMillis()
                                if (now - lastReportTime >= reportIntervalMs || bytesUploaded == fileSize) {
                                    lastReportTime = now
                                    env.progressListener?.onProgressUpdate(WorkerProgress((bytesUploaded * 100 / fileSize).toInt(), "Uploading"))
                                }
                            }
                        }
                    }
                    channel.writeStringUtf8("\r\n--$boundary--\r\n")
                }
            })
        }

        return if (response.status.isSuccess()) WorkerResult.Success("Uploaded")
        else WorkerResult.Failure("HTTP ${response.status.value}")
    }
}
