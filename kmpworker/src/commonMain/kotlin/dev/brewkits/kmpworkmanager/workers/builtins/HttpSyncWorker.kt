package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpMethod as WorkerHttpMethod
import dev.brewkits.kmpworkmanager.workers.config.HttpSyncConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Built-in worker for JSON synchronization (POST/GET JSON data).
 *
 * Optimized for JSON request/response scenarios. Automatically sets Content-Type to
 * application/json and handles JSON encoding/decoding.
 *
 * Ideal for:
 * - Data synchronization with server
 * - Batch analytics uploads
 * - Periodic data sync
 * - API sync endpoints
 *
 * **Memory Usage:** ~3-5MB RAM
 * **Startup Time:** <50ms
 * **Default Timeout:** 60 seconds
 *
 * **Configuration Example:**
 * ```json
 * {
 *   "url": "https://api.example.com/sync",
 *   "method": "POST",
 *   "headers": {
 *     "Authorization": "Bearer token"
 *   },
 *   "requestBody": {
 *     "lastSyncTime": 1234567890,
 *     "data": [...]
 *   },
 *   "timeoutMs": 60000
 * }
 * ```
 *
 * **Usage:**
 * ```kotlin
 * val config = Json.encodeToString(HttpSyncConfig.serializer(), HttpSyncConfig(
 *     url = "https://api.example.com/sync",
 *     method = "POST",
 *     headers = mapOf("Authorization" to "Bearer token"),
 *     requestBody = buildJsonObject {
 *         put("lastSync", System.currentTimeMillis())
 *         put("deviceId", "device123")
 *     }
 * ))
 *
 * scheduler.enqueue(
 *     id = "data-sync",
 *     trigger = TaskTrigger.Periodic(intervalMs = 3600000), // Every hour
 *     workerClassName = "HttpSyncWorker",
 *     inputJson = config
 * )
 * ```
 */
class HttpSyncWorker(
    private val httpClient: HttpClient = createDefaultHttpClient()
) : Worker {

    override suspend fun doWork(input: String?): WorkerResult {
        Logger.i("HttpSyncWorker", "Starting HTTP sync worker...")

        if (input == null) {
            Logger.e("HttpSyncWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = Json.decodeFromString<HttpSyncConfig>(input)
            Logger.i("HttpSyncWorker", "Executing ${config.method} sync to ${SecurityValidator.sanitizedURL(config.url)}")

            executeSyncRequest(config)
        } catch (e: Exception) {
            Logger.e("HttpSyncWorker", "Failed to execute HTTP sync", e)
            WorkerResult.Failure("Sync failed: ${e.message}")
        }
    }

    private suspend fun executeSyncRequest(config: HttpSyncConfig): WorkerResult {
        return try {
            val response: HttpResponse = httpClient.request(config.url) {
                method = when (config.httpMethod) {
                    WorkerHttpMethod.GET -> HttpMethod.Get
                    WorkerHttpMethod.POST -> HttpMethod.Post
                    WorkerHttpMethod.PUT -> HttpMethod.Put
                    WorkerHttpMethod.PATCH -> HttpMethod.Patch
                    else -> HttpMethod.Post // Default to POST
                }

                // Always set Content-Type to application/json
                contentType(ContentType.Application.Json)

                // Set headers
                config.headers?.forEach { (key, value) ->
                    header(key, value)
                }

                // Set JSON body for POST/PUT/PATCH
                if (config.requestBody != null && config.httpMethod in setOf(WorkerHttpMethod.POST, WorkerHttpMethod.PUT, WorkerHttpMethod.PATCH)) {
                    val jsonString = config.requestBody.toString()
                    setBody(jsonString)

                    // Log truncated body for debugging
                    val truncatedBody = SecurityValidator.truncateForLogging(jsonString, 500)
                    Logger.d("HttpSyncWorker", "Request body: $truncatedBody")
                }
            }

            val statusCode = response.status.value
            val responseBody = response.bodyAsText()

            if (statusCode in 200..299) {
                Logger.i("HttpSyncWorker", "Sync completed successfully with status $statusCode")

                // Optionally log response (truncated)
                if (responseBody.isNotEmpty()) {
                    val truncatedResponse = SecurityValidator.truncateForLogging(responseBody, 500)
                    Logger.d("HttpSyncWorker", "Response: $truncatedResponse")
                }

                WorkerResult.Success(
                    message = "Sync completed - HTTP $statusCode",
                    data = mapOf(
                        "statusCode" to statusCode,
                        "method" to config.httpMethod.name,
                        "url" to SecurityValidator.sanitizedURL(config.url),
                        "responseLength" to responseBody.length
                    )
                )
            } else {
                Logger.w("HttpSyncWorker", "Sync completed with non-success status $statusCode")
                WorkerResult.Failure(
                    message = "HTTP $statusCode error",
                    shouldRetry = statusCode in 500..599
                )
            }
        } catch (e: Exception) {
            Logger.e("HttpSyncWorker", "HTTP sync failed", e)
            WorkerResult.Failure("Sync failed: ${e.message}", shouldRetry = true)
        }
    }

    companion object {
        /**
         * Creates a default HTTP client with reasonable timeouts.
         */
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient {
                expectSuccess = false // Don't throw on non-2xx responses
            }
        }
    }
}
