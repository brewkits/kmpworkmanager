package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.config.TokenRefreshConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents `TokenRefresh`'s current concurrency behavior — it deliberately has no shared
 * dedup/mutex. Each `HttpRequestWorker.doWork()` call refreshes the token independently, so N
 * concurrent 401s trigger N refresh calls, not 1 shared one ("thundering herd").
 *
 * This is a documented, accepted tradeoff, not a bug: `executeWithTokenRefresh` has no
 * cross-instance state to synchronize on Android (each `Worker` can run in its own process),
 * so a `Mutex` in commonMain would only dedupe within a single process anyway — added
 * complexity without a correctness payoff. This test exists to catch a *regression* (a crash,
 * a corrupted response, a hung coroutine) if that assumption ever changes, not to enforce
 * request deduplication.
 */
class TokenRefreshConcurrencyTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }

    @Test
    fun concurrentTokenRefresh_eachCallTriggersIndependentRefresh_noSharedDedupNoCrash() = runTest {
        var refreshCallCount = 0
        val mock = MockEngine { request ->
            when {
                request.url.encodedPath == "/refresh" -> {
                    refreshCallCount++
                    respond(
                        content = """{"access_token":"token-$refreshCallCount"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                request.headers[HttpHeaders.Authorization]?.startsWith("Bearer token-") == true -> {
                    respond(content = "protected data", status = HttpStatusCode.OK)
                }
                else -> respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
            }
        }
        val worker = HttpRequestWorker(mockClient(mock))
        val refresh = TokenRefreshConfig(refreshUrl = "https://api.example.com/refresh")
        val config = HttpRequestConfig(url = "https://api.example.com/protected", tokenRefresh = refresh)
        val input = HttpWorkerJson.encodeToString(config)
        val concurrentCallCount = 10

        val results = coroutineScope {
            (1..concurrentCallCount)
                .map { async { worker.doWork(input, WorkerEnvironment(null) { false }) } }
                .awaitAll()
        }

        assertTrue(results.all { it is WorkerResult.Success }, "expected every concurrent call to succeed, got: $results")
        // Documents the no-dedup behavior: each of the N concurrent 401s independently
        // triggered its own refresh call — none were coalesced into a single shared refresh.
        assertEquals(concurrentCallCount, refreshCallCount)
    }
}
