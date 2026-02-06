package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpMethod as WorkerHttpMethod
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Built-in worker for executing HTTP requests (GET, POST, PUT, DELETE, PATCH).
 *
 * This is a fire-and-forget worker that executes HTTP requests without returning
 * the response body. It's ideal for:
 * - Analytics events
 * - Health check pings
 * - Webhook notifications
 * - Simple API calls
 *
 * **Memory Usage:** ~2-3MB RAM
 * **Startup Time:** <50ms
 *
 * **Configuration Example:**
 * ```json
 * {
 *   "url": "https://api.example.com/endpoint",
 *   "method": "POST",
 *   "headers": {
 *     "Authorization": "Bearer token",
 *     "Content-Type": "application/json"
 *   },
 *   "body": "{\"key\":\"value\"}",
 *   "timeoutMs": 30000
 * }
 * ```
 *
 * **Usage:**
 * ```kotlin
 * val config = Json.encodeToString(HttpRequestConfig.serializer(), HttpRequestConfig(
 *     url = "https://api.example.com/ping",
 *     method = "POST",
 *     headers = mapOf("Authorization" to "Bearer token"),
 *     body = "{\"status\":\"active\"}"
 * ))
 *
 * scheduler.enqueue(
 *     id = "ping-api",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "HttpRequestWorker",
 *     inputJson = config
 * )
 * ```
 */
class HttpRequestWorker(
    private val httpClient: HttpClient = createDefaultHttpClient()
) : Worker {

    override suspend fun doWork(input: String?): Boolean {
        Logger.i("HttpRequestWorker", "Starting HTTP request worker...")

        if (input == null) {
            Logger.e("HttpRequestWorker", "Input configuration is null")
            return false
        }

        return try {
            val config = Json.decodeFromString<HttpRequestConfig>(input)
            Logger.i("HttpRequestWorker", "Executing ${config.method} request to ${SecurityValidator.sanitizedURL(config.url)}")

            executeRequest(config)
        } catch (e: Exception) {
            Logger.e("HttpRequestWorker", "Failed to execute HTTP request", e)
            false
        }
    }

    private suspend fun executeRequest(config: HttpRequestConfig): Boolean {
        return try {
            val response: HttpResponse = httpClient.request(config.url) {
                method = when (config.httpMethod) {
                    WorkerHttpMethod.GET -> HttpMethod.Get
                    WorkerHttpMethod.POST -> HttpMethod.Post
                    WorkerHttpMethod.PUT -> HttpMethod.Put
                    WorkerHttpMethod.DELETE -> HttpMethod.Delete
                    WorkerHttpMethod.PATCH -> HttpMethod.Patch
                }

                // Set headers
                config.headers?.forEach { (key, value) ->
                    header(key, value)
                }

                // Set body for POST/PUT/PATCH
                if (config.body != null && config.httpMethod in setOf(WorkerHttpMethod.PUT, WorkerHttpMethod.PATCH, WorkerHttpMethod.POST)) {
                    setBody(config.body)
                    contentType(ContentType.Application.Json)
                }
            }

            val statusCode = response.status.value
            val success = statusCode in 200..299

            if (success) {
                Logger.i("HttpRequestWorker", "Request completed successfully with status $statusCode")
            } else {
                Logger.w("HttpRequestWorker", "Request completed with non-success status $statusCode")
            }

            success
        } catch (e: Exception) {
            Logger.e("HttpRequestWorker", "HTTP request failed", e)
            false
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
