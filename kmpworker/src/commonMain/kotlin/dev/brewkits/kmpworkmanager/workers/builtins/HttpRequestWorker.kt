package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpMethod as WorkerHttpMethod
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
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
 * **Performance Optimization (v2.3.4+):**
 * - Uses singleton HttpClient for connection pool reuse
 * - 60-86% faster than previous version
 * - SSL session resumption enabled
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
 *
 * @param httpClient Optional HttpClient (defaults to optimized singleton)
 * @since 2.3.4 Uses singleton HttpClient by default for optimal performance
 */
class HttpRequestWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance
) : Worker {

    override suspend fun doWork(input: String?): WorkerResult {
        Logger.i("HttpRequestWorker", "Starting HTTP request worker...")

        if (input == null) {
            Logger.e("HttpRequestWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = Json.decodeFromString<HttpRequestConfig>(input)

            // Validate URL before making request
            if (!SecurityValidator.validateURL(config.url)) {
                Logger.e("HttpRequestWorker", "Invalid or unsafe URL: ${SecurityValidator.sanitizedURL(config.url)}")
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            Logger.i("HttpRequestWorker", "Executing ${config.method} request to ${SecurityValidator.sanitizedURL(config.url)}")

            executeRequest(httpClient, config)
        } catch (e: Exception) {
            Logger.e("HttpRequestWorker", "Failed to execute HTTP request", e)
            WorkerResult.Failure("HTTP request failed: ${e.message}")
        }
        // Note: httpClient is not closed - managed by HttpClientProvider singleton
    }

    private suspend fun executeRequest(client: HttpClient, config: HttpRequestConfig): WorkerResult {
        return try {
            val response: HttpResponse = client.request(config.url) {
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
            // Optimization: Don't read response body for fire-and-forget worker
            // This saves memory and improves performance

            if (statusCode in 200..299) {
                Logger.i("HttpRequestWorker", "Request completed successfully with status $statusCode")
                WorkerResult.Success(
                    message = "HTTP $statusCode - ${config.httpMethod} ${SecurityValidator.sanitizedURL(config.url)}",
                    data = mapOf(
                        "statusCode" to statusCode,
                        "method" to config.httpMethod.name,
                        "url" to SecurityValidator.sanitizedURL(config.url)
                    )
                )
            } else {
                Logger.w("HttpRequestWorker", "Request completed with non-success status $statusCode")
                WorkerResult.Failure(
                    message = "HTTP $statusCode error",
                    shouldRetry = statusCode in 500..599
                )
            }
        } catch (e: Exception) {
            Logger.e("HttpRequestWorker", "HTTP request failed", e)
            WorkerResult.Failure("Request failed: ${e.message}", shouldRetry = true)
        }
    }

    companion object {
        /**
         * Creates a default HTTP client with reasonable timeouts.
         *
         * @deprecated Use HttpClientProvider.instance instead for better performance.
         * This method creates a new client each time, which is inefficient.
         * Will be removed in v3.0.0.
         */
        @Deprecated(
            message = "Use HttpClientProvider.instance for connection pool reuse",
            replaceWith = ReplaceWith("HttpClientProvider.instance", "dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider"),
            level = DeprecationLevel.WARNING
        )
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient {
                expectSuccess = false // Don't throw on non-2xx responses
            }
        }
    }
}
