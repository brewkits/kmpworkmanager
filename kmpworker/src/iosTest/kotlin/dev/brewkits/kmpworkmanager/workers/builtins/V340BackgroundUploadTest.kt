@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.config.IosBackgroundUploadConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the 3.4.0 Background URLSession upload variant — the counterpart
 * to [BackgroundDownloadStateStoreTest] for uploads, plus the `TransferType`/`sourcePath`
 * additions to [BackgroundDownloadStateStore.Entry].
 */
class V340BackgroundUploadTest {

    private val testRunId: String = "test-${kotlin.random.Random.nextInt(0, 1_000_000)}"

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        BackgroundDownloadStateStore.clearForTest()
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        BackgroundDownloadStateStore.clearForTest()
    }

    // ==================== Entry backward compatibility ====================

    @Test
    fun `Entry defaults transferType to DOWNLOAD for backward compatibility`() {
        // A pre-3.4.0 caller never set transferType/sourcePath — must decode as before.
        val entry = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-legacy",
            taskIdentifier = 1L,
            savePath = "/var/legacy.bin",
            workerName = "LegacyWorker",
            createdAtMs = 1L,
        )
        assertEquals(BackgroundDownloadStateStore.TransferType.DOWNLOAD, entry.transferType)
        assertEquals(null, entry.sourcePath)
    }

    @Test
    fun `Entry with UPLOAD transferType round-trips through put and get`() = runBlocking<Unit> {
        val entry = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-upload",
            taskIdentifier = 5L,
            savePath = "",
            workerName = "UploadWorker",
            createdAtMs = 1L,
            transferType = BackgroundDownloadStateStore.TransferType.UPLOAD,
            sourcePath = "/var/mobile/Documents/report.pdf",
        )
        BackgroundDownloadStateStore.put(entry)

        val loaded = BackgroundDownloadStateStore.get("$testRunId-upload", 5L)
        assertEquals(entry, loaded)
        assertEquals(BackgroundDownloadStateStore.TransferType.UPLOAD, loaded?.transferType)
        assertEquals("/var/mobile/Documents/report.pdf", loaded?.sourcePath)
    }

    @Test
    fun `pre-3_6_0 JSON without transferType or sourcePath decodes as a legacy download entry`() {
        // Simulates a state file written by a pre-3.4.0 build — proves the schema-evolution
        // guarantee the class KDoc promises (ignoreUnknownKeys / additive fields only).
        val json = Json { ignoreUnknownKeys = true }
        val legacyJson = """
            {"sessionIdentifier":"old-session","taskIdentifier":42,"savePath":"/var/old.bin","workerName":"OldWorker","createdAtMs":1000}
        """.trimIndent()
        val decoded = json.decodeFromString(BackgroundDownloadStateStore.Entry.serializer(), legacyJson)
        assertEquals(BackgroundDownloadStateStore.TransferType.DOWNLOAD, decoded.transferType)
        assertEquals(null, decoded.sourcePath)
        assertEquals("old-session", decoded.sessionIdentifier)
    }

    // ==================== IosBackgroundUploadConfig validation ====================

    @Test
    fun `IosBackgroundUploadConfig rejects non-http url`() {
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundUploadConfig(url = "ftp://example.com/x", filePath = "/tmp/x")
        }
    }

    @Test
    fun `IosBackgroundUploadConfig rejects blank filePath`() {
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundUploadConfig(url = "https://example.com/upload", filePath = "")
        }
    }

    @Test
    fun `IosBackgroundUploadConfig defaults httpMethod to POST`() {
        val config = IosBackgroundUploadConfig(url = "https://example.com/upload", filePath = "/tmp/x")
        assertEquals("POST", config.httpMethod)
        assertEquals(null, config.sharedContainerIdentifier)
    }

    @Test
    fun `IosBackgroundUploadConfig serializes round-trip via KmpWorkManagerRuntime json`() {
        val config = IosBackgroundUploadConfig(
            url = "https://example.com/upload",
            filePath = "/tmp/report.pdf",
            sessionIdentifier = "test.bgupload",
            httpMethod = "PUT",
            sharedContainerIdentifier = "group.dev.brewkits.kmpworkmanager"
        )
        val json = Json.encodeToString(IosBackgroundUploadConfig.serializer(), config)
        val decoded = Json.decodeFromString(IosBackgroundUploadConfig.serializer(), json)
        assertEquals(config, decoded)
    }

    // ==================== IosBackgroundUploadWorker ====================

    private fun makeTempFile(name: String, content: String): String {
        val path = NSTemporaryDirectory() + name
        val nsContent = NSString.create(string = content)
        nsContent.dataUsingEncoding(NSUTF8StringEncoding)!!
            .writeToFile(path, atomically = true)
        return path
    }

    @Test
    fun `worker fails fast when input is null`() = runTest {
        val worker = IosBackgroundUploadWorker()
        val result = worker.doWork(null, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Failure)
    }

    @Test
    fun `worker fails when source file does not exist on disk`() = runTest {
        val config = IosBackgroundUploadConfig(
            url = "https://example.com/upload",
            filePath = "/tmp/kmp_nonexistent_${testRunId}.bin"
        )
        val input = Json.encodeToString(IosBackgroundUploadConfig.serializer(), config)
        val worker = IosBackgroundUploadWorker()
        val result = worker.doWork(input, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Failure, "Missing source file must fail before submission")
    }

    @Test
    fun `worker submits upload and returns Success when source file exists`() = runTest {
        val path = makeTempFile("kmp_upload_test_$testRunId.txt", "hello world")
        val config = IosBackgroundUploadConfig(
            url = "https://example.com/upload",
            filePath = path,
            sessionIdentifier = "test.bgupload.$testRunId"
        )
        val input = Json.encodeToString(IosBackgroundUploadConfig.serializer(), config)
        val worker = IosBackgroundUploadWorker()
        val result = worker.doWork(input, WorkerEnvironment(null) { false })
        assertTrue(result is WorkerResult.Success, "Expected Success (OS-accepted), got $result")
    }
}
