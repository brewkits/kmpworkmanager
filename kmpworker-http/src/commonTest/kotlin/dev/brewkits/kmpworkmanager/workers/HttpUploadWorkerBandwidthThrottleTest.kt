package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers [HttpUploadConfig.maxBytesPerSecond] parsing/round-trip through [HttpUploadWorker].
 *
 * **No timing assertion here, unlike the download-side equivalent
 * (`HttpDownloadWorkerBandwidthThrottleTest`).** `HttpUploadWorker` streams the file body via
 * `OutgoingContent.WriteChannelContent.writeTo(channel)` — the same shape
 * `ParallelHttpUploadWorkerTest`'s own comment already flags as a cross-K/N-target gap in
 * `ktor-client-mock` ("toByteArray() is not available across all K/N targets"). A MockEngine
 * handler that never reads `request.body` (as every handler in this file and
 * `ParallelHttpUploadWorkerTest` does, by that same constraint) never invokes `writeTo` at all —
 * confirmed empirically: a throttled run here completed in ~1ms instead of the expected ~500ms,
 * because the byte-copy loop containing `throttle.consume(...)` never executed.
 *
 * The throttle's actual behavior (including the shared-budget-across-concurrent-writers
 * property `ParallelHttpUploadWorker` depends on) is fully covered by `BandwidthThrottleTest`.
 * The wiring here — `throttle?.consume(bytes.size)` right after each `channel.writeFully(bytes)`
 * — is the same one-line pattern already verified end-to-end on the download side
 * (`HttpDownloadWorkerBandwidthThrottleTest`), just symmetrically applied to the mirrored
 * upload loop. These tests confirm the config field is accepted and doesn't break a real
 * (if MockEngine-shortened) upload, not the timing itself.
 */
class HttpUploadWorkerBandwidthThrottleTest {

    private fun mockClient() = HttpClient(MockEngine { _ ->
        respond(content = "OK", status = HttpStatusCode.OK)
    }) { install(HttpTimeout) }

    private fun writeTempFile(sizeBytes: Int): String {
        val path = "test_upload_throttle_${kotlin.random.Random.nextInt()}.bin".toPath()
        FileSystem.SYSTEM.write(path) { write(ByteArray(sizeBytes) { it.toByte() }) }
        return path.toString()
    }

    @Test
    fun `maxBytesPerSecond is accepted and the upload still succeeds`() = runTest {
        val filePath = writeTempFile(200)
        val fs = FileSystem.SYSTEM
        val worker = HttpUploadWorker(mockClient(), fs)
        val config = HttpUploadConfig(
            url = "https://example.com/upload",
            filePath = filePath,
            maxBytesPerSecond = 400L
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "expected Success: $result")
        } finally {
            fs.delete(filePath.toPath())
        }
    }
}
