package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Confirms [HttpDownloadConfig.maxBytesPerSecond] is actually wired into
 * [HttpDownloadWorker]'s byte-copy loop, not just accepted and ignored.
 *
 * Note on timing: `HttpDownloadWorker`'s copy loop runs inside `withContext(AppDispatchers.IO)`,
 * which on the JVM is the real `Dispatchers.IO` — NOT the `runTest` virtual-time dispatcher.
 * `BandwidthThrottle`'s `delay()` calls here are therefore REAL wall-clock delays, unlike the
 * exact virtual-time assertions in `BandwidthThrottleTest`. This test uses a tiny payload and a
 * generous lower-bound tolerance (well under the theoretical minimum) specifically to stay fast
 * and non-flaky while still proving the wiring end-to-end.
 */
class HttpDownloadWorkerBandwidthThrottleTest {

    private fun mockClient(payload: ByteArray) = HttpClient(MockEngine { _ ->
        respond(
            content = payload,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString()))
        )
    }) { install(HttpTimeout) }

    @Test
    fun `maxBytesPerSecond measurably slows down a download`() = runTest {
        // 200 bytes at 400 B/s should take ~500ms in theory; assert only that it took at
        // least 200ms — comfortably below the theoretical minimum to absorb scheduler
        // jitter, while still far above what an unthrottled MockEngine transfer takes
        // (single-digit ms).
        val payload = ByteArray(200) { it.toByte() }
        val savePath = "test_throttled_dl_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = HttpDownloadWorker(mockClient(payload), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false,
            maxBytesPerSecond = 400L
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val startedAt = currentTimeMillis()
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            val elapsedMs = currentTimeMillis() - startedAt

            assertTrue(result is WorkerResult.Success, "expected Success: $result")
            assertTrue(
                elapsedMs >= 200L,
                "throttled download of 200 bytes at 400 B/s took only ${elapsedMs}ms — " +
                    "maxBytesPerSecond does not appear to be enforced"
            )
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }

    @Test
    fun `no maxBytesPerSecond means no artificial delay`() = runTest {
        val payload = ByteArray(200) { it.toByte() }
        val savePath = "test_unthrottled_dl_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val worker = HttpDownloadWorker(mockClient(payload), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false
            // maxBytesPerSecond left null (default)
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val startedAt = currentTimeMillis()
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            val elapsedMs = currentTimeMillis() - startedAt

            assertTrue(result is WorkerResult.Success, "expected Success: $result")
            assertTrue(
                elapsedMs < 200L,
                "unthrottled 200-byte download took ${elapsedMs}ms — expected near-instant, " +
                    "something is delaying it even with maxBytesPerSecond unset"
            )
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }
}
