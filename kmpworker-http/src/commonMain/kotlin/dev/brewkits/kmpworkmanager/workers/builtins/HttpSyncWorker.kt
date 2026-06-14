package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpSyncConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Built-in worker for standard API data synchronization.
 */
class HttpSyncWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        Logger.i("HttpSyncWorker", "Starting data sync worker...")

        if (input == null) {
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = HttpWorkerJson.decodeFromString<HttpSyncConfig>(input)

            if (!SecurityValidator.validateURL(config.url)) {
                return WorkerResult.Failure("Invalid sync URL")
            }

            syncData(httpClient, config)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network exceptions are transient — see HttpUploadWorker comment for full
            // rationale (v2.5 chain semantics treat shouldRetry=false as immediate-abandon).
            Logger.e("HttpSyncWorker", "Sync failed", e)
            WorkerResult.Failure("Sync failed: ${e.message}", shouldRetry = true)
        }
    }

    // Exceptions propagate to the caller's catch (in `doWork` above) — that's where
    // they're tagged shouldRetry=true. The pre-fix wrapped this body in
    // `try { ... } catch (Exception) { throw e }` which was a no-op smell.
    private suspend fun syncData(client: HttpClient, config: HttpSyncConfig): WorkerResult {
        val response: HttpResponse = client.request(config.url) {
            method = when (config.method.uppercase()) {
                "GET" -> HttpMethod.Get
                "POST" -> HttpMethod.Post
                "PUT" -> HttpMethod.Put
                "PATCH" -> HttpMethod.Patch
                else -> HttpMethod.Post
            }
            SecurityValidator.sanitizeHeaders(config.headers)?.forEach { (key, value) -> header(key, value) }
            if (config.requestBody != null) {
                setBody(config.requestBody)
                contentType(ContentType.Application.Json)
            }
        }

        return if (response.status.isSuccess()) {
            WorkerResult.Success(
                message = "Sync complete",
                data = buildJsonObject {
                    put("url", config.url)
                    put("status", response.status.value)
                }
            )
        } else {
            WorkerResult.Failure("HTTP ${response.status.value} error", shouldRetry = response.status.value >= 500)
        }
    }
}
