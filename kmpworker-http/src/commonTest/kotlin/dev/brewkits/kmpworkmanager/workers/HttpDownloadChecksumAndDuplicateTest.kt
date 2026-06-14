package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.config.ChecksumAlgorithm
import dev.brewkits.kmpworkmanager.workers.config.DuplicatePolicy
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the v2.5 additions on top of HttpDownloadWorker:
 *  - DuplicatePolicy (overwrite / skip / rename)
 *  - Checksum verification (SHA-256 happy path + mismatch)
 *
 * MockEngine + Okio's `FileSystem.SYSTEM` are sufficient — these are pure logic tests
 * exercising the worker's branching, not network behaviour.
 */
class HttpDownloadChecksumAndDuplicateTest {

    private fun mockClient(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }

    // SHA-256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
    private val helloWorldSha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

    @Test
    fun checksum_match_returnsSuccess_andFinalizesFile() = runTest {
        val savePath = "test_checksum_ok_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        val mock = MockEngine { _ -> respond(content = "hello world", status = HttpStatusCode.OK) }
        val worker = HttpDownloadWorker(mockClient(mock), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false,
            expectedChecksum = helloWorldSha256,
            checksumAlgorithm = ChecksumAlgorithm.SHA256
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "expected Success on matching SHA-256: $result")
            assertTrue(fs.exists(savePath))
            assertEquals("hello world", fs.source(savePath).buffer().readUtf8())
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }

    @Test
    fun checksum_mismatch_returnsFailure_andDeletesPartial() = runTest {
        val savePath = "test_checksum_bad_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "${savePath}.partial".toPath()
        val fs = FileSystem.SYSTEM
        val mock = MockEngine { _ -> respond(content = "different content", status = HttpStatusCode.OK) }
        val worker = HttpDownloadWorker(mockClient(mock), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false,
            expectedChecksum = helloWorldSha256, // expects "hello world" digest
            checksumAlgorithm = ChecksumAlgorithm.SHA256
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Failure, "expected Failure on checksum mismatch: $result")
            assertTrue((result as WorkerResult.Failure).message.contains("mismatch", ignoreCase = true))
            assertFalse(fs.exists(savePath), "savePath must NOT exist when checksum failed")
            assertFalse(fs.exists(partialPath), "partial must be deleted on checksum mismatch")
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            if (fs.exists(partialPath)) fs.delete(partialPath)
        }
    }

    @Test
    fun duplicatePolicy_skip_returnsSuccessWithoutNetworkCall() = runTest {
        val savePath = "test_skip_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        fs.write(savePath) { writeUtf8("existing content") }

        // MockEngine that fails if invoked — proves we did NOT issue a network request.
        var networkCalled = false
        val mock = MockEngine { _ ->
            networkCalled = true
            respond(content = "should not be downloaded", status = HttpStatusCode.OK)
        }
        val worker = HttpDownloadWorker(mockClient(mock), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false,
            onDuplicate = DuplicatePolicy.SKIP
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Expected Success when SKIP + file exists: $result")
            assertFalse(networkCalled, "SKIP must short-circuit before any HTTP request")
            assertEquals("existing content", fs.source(savePath).buffer().readUtf8())
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }

    @Test
    fun duplicatePolicy_rename_savesToSuffixedPath_andLeavesOriginalAlone() = runTest {
        val original = "test_rename_${kotlin.random.Random.nextInt()}.txt".toPath()
        val expectedRenamed = "${original.toString().removeSuffix(".txt")}_1.txt".toPath()
        val fs = FileSystem.SYSTEM
        fs.write(original) { writeUtf8("original") }

        val mock = MockEngine { _ -> respond(content = "downloaded", status = HttpStatusCode.OK) }
        val worker = HttpDownloadWorker(mockClient(mock), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = original.toString(),
            resumable = false,
            onDuplicate = DuplicatePolicy.RENAME
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success, "Expected Success on RENAME: $result")

            assertTrue(fs.exists(original), "original must still exist after RENAME")
            assertEquals("original", fs.source(original).buffer().readUtf8())

            assertTrue(fs.exists(expectedRenamed), "renamed file must exist at suffixed path")
            assertEquals("downloaded", fs.source(expectedRenamed).buffer().readUtf8())
        } finally {
            if (fs.exists(original)) fs.delete(original)
            if (fs.exists(expectedRenamed)) fs.delete(expectedRenamed)
        }
    }

    @Test
    fun duplicatePolicy_overwrite_isDefault_andReplacesExisting() = runTest {
        val savePath = "test_overwrite_${kotlin.random.Random.nextInt()}.bin".toPath()
        val fs = FileSystem.SYSTEM
        fs.write(savePath) { writeUtf8("old") }

        val mock = MockEngine { _ -> respond(content = "new", status = HttpStatusCode.OK) }
        val worker = HttpDownloadWorker(mockClient(mock), fs)
        val config = HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath.toString(),
            resumable = false
            // onDuplicate = OVERWRITE by default
        )
        val input = HttpWorkerJson.encodeToString(config)

        try {
            val result = worker.doWork(input, WorkerEnvironment(null) { false })
            assertTrue(result is WorkerResult.Success)
            assertEquals("new", fs.source(savePath).buffer().readUtf8())
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
        }
    }

    @Test
    fun config_rejectsNonHexChecksum() {
        // Build-time validation — should throw at construction, before any worker runs.
        try {
            HttpDownloadConfig(
                url = "https://example.com/",
                savePath = "/tmp/x",
                expectedChecksum = "not-a-hex-string!"
            )
            error("Constructor should have thrown for non-hex checksum")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("hex-encoded", ignoreCase = true))
        }
    }
}
