package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Built-in worker for executing HTTP requests (GET, POST, PUT, DELETE, PATCH).
 */
class HttpRequestWorker(
    private val httpClient: HttpClient = HttpClientProvider.instance
) : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        Logger.i("HttpRequestWorker", "Starting HTTP request worker...")

        if (input == null) {
            Logger.e("HttpRequestWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = KmpWorkManagerRuntime.json.decodeFromString<HttpRequestConfig>(input)

            // Validate URL before making request
            if (!SecurityValidator.validateURL(config.url)) {
                Logger.e("HttpRequestWorker", "Invalid or unsafe URL: ${SecurityValidator.sanitizedURL(config.url)}")
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            Logger.i("HttpRequestWorker", "Executing ${config.httpMethod} request to ${SecurityValidator.sanitizedURL(config.url)}")

            executeRequest(httpClient, config)
        } catch (e: Exception) {
            Logger.e("HttpRequestWorker", "Failed to execute HTTP request", e)
            WorkerResult.Failure("HTTP request failed: ${e.message}")
        }
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

            if (statusCode in 200..299) {
                Logger.i("HttpRequestWorker", "Request completed successfully with status $statusCode")
                WorkerResult.Success(
                    message = "HTTP $statusCode - ${config.httpMethod} ${SecurityValidator.sanitizedURL(config.url)}",
                    data = buildJsonObject {
                        put("statusCode", statusCode)
                        put("method", config.httpMethod.name)
                        put("url", SecurityValidator.sanitizedURL(config.url))
                    }
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
}
