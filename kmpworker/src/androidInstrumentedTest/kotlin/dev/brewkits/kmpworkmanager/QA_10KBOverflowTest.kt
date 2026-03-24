@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class)

package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA Test: Android WorkManager 10KB Data Limit — Overflow to File
 *
 * WorkManager caps Data at 10 240 bytes total. The library's buildWorkData() helper
 * silently routes large inputs through a temp file in cacheDir instead of the Data bundle.
 *
 * Verification strategy: WorkInfo does NOT expose inputData publicly, so we check
 * for the existence / absence of `kmp_input_*` temp files in cacheDir.
 * Tasks are scheduled with a 60s delay so the worker never runs during the test,
 * keeping the temp file on disk long enough to inspect.
 *
 * Run:
 * ```
 * ./gradlew :kmpworker:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.brewkits.kmpworkmanager.QA_10KBOverflowTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class QA_10KBOverflowTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: BackgroundTaskScheduler

    private val workerFactory = object : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = null
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)

        KmpWorkManager.initialize(
            context = context,
            workerFactory = workerFactory,
            config = KmpWorkManagerConfig()
        )
        scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        // Clean up any leftover overflow files from previous runs
        overflowFiles().forEach { it.delete() }
    }

    @After
    fun tearDown() {
        workManager.cancelAllWorkByTag(NativeTaskScheduler.TAG_KMP_TASK)
        overflowFiles().forEach { it.delete() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-01: Small JSON (< 8 KB) — must NOT create an overflow file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc01_smallJson_noOverflowFile_storedInline() = runBlocking {
        val smallJson = """{"key":"${"a".repeat(100)}"}"""
        assertTrue(smallJson.encodeToByteArray().size < 8_192, "Precondition: JSON must be < 8 KB")

        val result = scheduler.enqueue(
            id = "tc01-small-json",
            trigger = TaskTrigger.OneTime(initialDelayMs = 60_000), // long delay — worker must not run during test
            workerClassName = "TestWorker",
            inputJson = smallJson,
            policy = ExistingPolicy.REPLACE
        )
        assertEquals(ScheduleResult.ACCEPTED, result, "Small JSON task must be accepted")

        delay(500)

        val files = overflowFiles()
        assertTrue(files.isEmpty(),
            "Small JSON (< 8 KB) must NOT create an overflow file. Found: ${files.map { it.name }}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-02: Large JSON (15 KB) — MUST overflow to file, must NOT crash
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc02_largeJson_overflowToFile_noWorkManagerCrash() = runBlocking {
        val largeJson = buildLargeJson(targetSizeBytes = 15_000)
        val jsonBytes = largeJson.encodeToByteArray().size
        assertTrue(jsonBytes > 10_240,
            "Precondition: JSON ($jsonBytes B) must exceed WorkManager 10 KB limit")

        // This call MUST NOT throw IllegalStateException: Data cannot occupy more than 10240 bytes
        val result = scheduler.enqueue(
            id = "tc02-large-json",
            trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
            workerClassName = "TestWorker",
            inputJson = largeJson,
            policy = ExistingPolicy.REPLACE
        )
        assertEquals(ScheduleResult.ACCEPTED, result,
            "Large JSON task must be accepted — scheduler must not crash with 10 KB limit")

        delay(500)

        val files = overflowFiles()
        assertTrue(files.isNotEmpty(),
            "Large JSON (> 8 KB) must create an overflow file in cacheDir")

        // Verify the file contains the complete original JSON (no truncation)
        val overflowFile = files.maxByOrNull { it.lastModified() }!!
        assertEquals(largeJson, overflowFile.readText(),
            "Overflow file must contain the complete original JSON — no truncation")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-03: Boundary — JSON exactly at threshold (8 192 B) stays inline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc03_boundaryJson_atThreshold_noOverflowFile() = runBlocking {
        // Build the largest JSON that still fits within the overflow threshold
        val thresholdJson = buildLargeJson(targetSizeBytes = 8_100) // safely below 8 192
        val byteSize = thresholdJson.encodeToByteArray().size
        assertTrue(byteSize <= 8_192,
            "Precondition: JSON ($byteSize B) must be at or below the 8 192-byte threshold")

        val result = scheduler.enqueue(
            id = "tc03-boundary-json",
            trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
            workerClassName = "TestWorker",
            inputJson = thresholdJson,
            policy = ExistingPolicy.REPLACE
        )
        assertEquals(ScheduleResult.ACCEPTED, result)

        delay(500)

        val files = overflowFiles()
        assertTrue(files.isEmpty(),
            "JSON at threshold (≤ 8 192 B) must NOT use overflow. Found: ${files.map { it.name }}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-04: Null inputJson — must not create an overflow file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc04_nullInputJson_noOverflowFile() = runBlocking {
        val result = scheduler.enqueue(
            id = "tc04-null-input",
            trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
            workerClassName = "TestWorker",
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )
        assertEquals(ScheduleResult.ACCEPTED, result)

        delay(500)

        val files = overflowFiles()
        assertTrue(files.isEmpty(),
            "Null inputJson must NOT create any overflow file. Found: ${files.map { it.name }}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-05: Very large JSON (50 KB) — overflow file holds full content
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc05_veryLargeJson_50KB_completeContentInFile() = runBlocking {
        val hugeJson = buildLargeJson(targetSizeBytes = 50_000)
        val byteSize = hugeJson.encodeToByteArray().size
        assertTrue(byteSize > 10_240, "Precondition: JSON must be > 10 KB ($byteSize B)")

        val result = scheduler.enqueue(
            id = "tc05-huge-json",
            trigger = TaskTrigger.OneTime(initialDelayMs = 60_000),
            workerClassName = "TestWorker",
            inputJson = hugeJson,
            policy = ExistingPolicy.REPLACE
        )
        assertEquals(ScheduleResult.ACCEPTED, result,
            "50 KB JSON must be accepted without crashing")

        delay(500)

        val files = overflowFiles()
        assertTrue(files.isNotEmpty(), "50 KB JSON must create an overflow file")

        val overflowFile = files.maxByOrNull { it.lastModified() }!!
        val storedContent = overflowFile.readText()
        assertEquals(hugeJson.length, storedContent.length,
            "Overflow file length must match original JSON — no truncation for 50 KB payload")
        assertEquals(hugeJson, storedContent,
            "Overflow file content must be byte-identical to original JSON")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns all kmp_input_* temp files currently in cacheDir. */
    private fun overflowFiles(): List<File> =
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("kmp_input_") && it.name.endsWith(".json") }
            ?: emptyList()

    /**
     * Builds a valid JSON array large enough to reach [targetSizeBytes].
     * Each item is ~50 bytes: {"id":NNN,"payload":"xxxxxxxxxxxxxxxxxxxx"}
     */
    private fun buildLargeJson(targetSizeBytes: Int): String {
        val payload = "x".repeat(20)
        val itemSize = """{"id":9999,"payload":"$payload"}""".length + 1 // +1 for comma
        val itemsNeeded = (targetSizeBytes / itemSize) + 5 // slight overshoot ensures target is reached

        return buildString {
            append("""{"items":[""")
            repeat(itemsNeeded) { i ->
                if (i > 0) append(',')
                append("""{"id":$i,"payload":"$payload"}""")
            }
            append("]}")
        }
    }
}
