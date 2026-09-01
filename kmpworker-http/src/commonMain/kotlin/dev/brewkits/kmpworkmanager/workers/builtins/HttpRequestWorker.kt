package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.HttpMethod as WorkerHttpMethod
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpClientProvider
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import dev.brewkits.kmpworkmanager.workers.utils.applyHmacSigning
import dev.brewkits.kmpworkmanager.workers.utils.executeWithTokenRefresh
import io.ktor.client.*
import io.ktor.client.plugins.*
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
            val config = HttpWorkerJson.decodeFromString<HttpRequestConfig>(input)

            // Validate URL before making request
            if (!SecurityValidator.validateURL(config.url)) {
                Logger.e("HttpRequestWorker", "Invalid or unsafe URL: ${SecurityValidator.sanitizedURL(config.url)}")
                return WorkerResult.Failure("Invalid or unsafe URL")
            }

            // TokenRefreshConfig.refreshUrl is a second outbound destination this worker
            // calls directly — it must pass the same SSRF gate as the primary URL, not
            // just the http(s):// prefix check TokenRefreshConfig.init does.
            config.tokenRefresh?.let { refresh ->
                if (!SecurityValidator.validateURL(refresh.refreshUrl)) {
                    Logger.e("HttpRequestWorker", "Invalid or unsafe refresh URL: ${SecurityValidator.sanitizedURL(refresh.refreshUrl)}")
                    return WorkerResult.Failure("Invalid or unsafe refresh URL")
                }
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
            val response: HttpResponse = executeWithTokenRefresh(client, config.tokenRefresh) { newToken ->
                client.request(config.url) {
                    method = when (config.httpMethod) {
                        WorkerHttpMethod.GET -> HttpMethod.Get
                        WorkerHttpMethod.POST -> HttpMethod.Post
                        WorkerHttpMethod.PUT -> HttpMethod.Put
                        WorkerHttpMethod.DELETE -> HttpMethod.Delete
                        WorkerHttpMethod.PATCH -> HttpMethod.Patch
                    }

                    timeout {
                        requestTimeoutMillis = config.timeoutMs
                        connectTimeoutMillis = config.timeoutMs
                        socketTimeoutMillis = config.timeoutMs
                    }

                    SecurityValidator.sanitizeHeaders(config.headers)?.forEach { (key, value) ->
                        header(key, value)
                    }

                    // Set body for POST/PUT/PATCH. Body-bearing methods only — a body sent
                    // in the config for GET/DELETE is never written to the wire, and must
                    // therefore also be excluded from what gets signed below.
                    val sendsBody = config.httpMethod in setOf(WorkerHttpMethod.PUT, WorkerHttpMethod.PATCH, WorkerHttpMethod.POST)
                    val bodyOnWire = config.body.takeIf { sendsBody }
                    if (bodyOnWire != null) {
                        setBody(bodyOnWire)
                        contentType(ContentType.Application.Json)
                    }

                    // A retried request (newToken != null) overrides whatever Authorization
                    // config.headers set above — the refreshed token is what must be sent.
                    // `header()` APPENDS (Ktor's HeadersBuilder), so the stale value set
                    // above must be removed first or the request would carry both.
                    val tokenRefresh = config.tokenRefresh
                    if (newToken != null && tokenRefresh != null) {
                        headers.remove(tokenRefresh.authHeaderName)
                        header(tokenRefresh.authHeaderName, "${tokenRefresh.authHeaderPrefix}$newToken")
                    }

                    // NOTE: computeHmacSignature signs METHOD/URL/BODY/TIMESTAMP only — it
                    // does NOT cover headers, so a refreshed Authorization header above is
                    // NOT part of what's signed. What DOES differ between the original and
                    // retried attempt is the timestamp (recomputed per call), so the two
                    // signatures differ even though the covered fields are otherwise the same.
                    // bodyOnWire (not config.body) — must match exactly what setBody sent,
                    // so a GET/DELETE with a config.body that's never written to the wire
                    // doesn't sign bytes the server will never see.
                    config.hmacSigning?.let { signingConfig ->
                        applyHmacSigning(config.httpMethod.name, config.url, bodyOnWire, signingConfig)
                    }
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
