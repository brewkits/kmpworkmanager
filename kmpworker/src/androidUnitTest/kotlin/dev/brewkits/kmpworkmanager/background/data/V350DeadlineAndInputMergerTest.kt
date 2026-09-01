package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android-side coverage for two features that are easy to ship broken because they
 * *look* wired up while doing nothing:
 *
 * 1. **`TaskRequest.deadlineMs`** — a stale task must be skipped, and skipped in a way that
 *    does not make WorkManager retry it forever.
 * 2. **`TaskRequest.mergeOutputFromPreviousStep`** — the InputMerger. Android cannot pass a
 *    `JsonObject` between steps directly; it round-trips through WorkManager output `Data`
 *    (`KEY_STEP_OUTPUT`) and back. The first implementation stamped the *flag* but never
 *    emitted or read the *payload*, so the feature silently no-opped on Android while
 *    working on iOS. These tests fail if that regresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V350DeadlineAndInputMergerTest {

    private lateinit var context: Context
    private val savedRecords = mutableListOf<ExecutionRecord>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        savedRecords.clear()
        KmpWorkManagerRuntime.executionHistoryStore = object : ExecutionHistoryStore {
            override suspend fun save(record: ExecutionRecord) { savedRecords += record }
            override suspend fun getRecords(limit: Int): List<ExecutionRecord> = savedRecords.toList()
            override suspend fun clear() { savedRecords.clear() }
        }
    }

    @After
    fun tearDown() {
        KmpWorkManagerRuntime.executionHistoryStore = null
        InputCapturingWorker.lastInputJson = null
    }

    // ── Deadline ──────────────────────────────────────────────────────────────

    @Test
    fun deadlineInThePast_skipsWorkerEntirely() {
        val outcome = runWorker(
            result = WorkerResult.Success(),
            deadlineMs = System.currentTimeMillis() - 60_000
        )
        assertTrue(outcome is Result.Success, "A missed deadline must not surface as a failure. Got: $outcome")
        assertNull(
            InputCapturingWorker.lastInputJson,
            "The worker body must never run once the deadline has passed — that is the whole point."
        )
    }

    @Test
    fun deadlineInThePast_returnsSuccessNotRetry_soWorkManagerStopsRescheduling() {
        // Result.retry() here would be a slow-motion disaster: the task can never meet a
        // deadline that is already gone, so it would retry until its backoff budget is spent.
        val outcome = runWorker(
            result = WorkerResult.Success(),
            deadlineMs = System.currentTimeMillis() - 60_000
        )
        assertTrue(outcome !is Result.Retry, "A deadline miss must never schedule a retry. Got: $outcome")
    }

    @Test
    fun deadlineInThePast_recordsSkippedStatusInHistory() {
        runWorker(result = WorkerResult.Success(), deadlineMs = System.currentTimeMillis() - 60_000)
        assertEquals(
            ExecutionStatus.SKIPPED, savedRecords.single().status,
            "History must distinguish 'skipped for staleness' from 'succeeded'."
        )
    }

    @Test
    fun deadlineInTheFuture_runsNormally() {
        runWorker(result = WorkerResult.Success(), deadlineMs = System.currentTimeMillis() + 60_000)
        assertNotNull(InputCapturingWorker.lastInputJson, "A future deadline must not block execution.")
        assertEquals(ExecutionStatus.SUCCESS, savedRecords.single().status)
    }

    @Test
    fun absentDeadline_runsNormally_backCompat() {
        runWorker(result = WorkerResult.Success(), deadlineMs = null)
        assertNotNull(InputCapturingWorker.lastInputJson)
        assertEquals(ExecutionStatus.SUCCESS, savedRecords.single().status)
    }

    // ── InputMerger ───────────────────────────────────────────────────────────

    @Test
    fun successfulWorker_publishesOutputForTheNextStep() {
        // This is the half that was missing: without an output Data payload,
        // WorkManager's OverwritingInputMerger has nothing to hand the successor.
        val outcome = runWorker(
            result = WorkerResult.Success(data = buildJsonObject { put("filePath", "/tmp/x.zip") })
        )
        val output = (outcome as Result.Success).outputData
        val raw = output.getString(NativeTaskScheduler.KEY_STEP_OUTPUT)
        assertNotNull(raw, "A successful worker must publish its data under KEY_STEP_OUTPUT.")
        assertEquals("/tmp/x.zip", (Json.parseToJsonElement(raw) as JsonObject)["filePath"]?.jsonPrimitive?.content)
    }

    @Test
    fun workerWithNullData_publishesNoOutput() {
        val outcome = runWorker(result = WorkerResult.Success(data = null))
        assertNull(
            (outcome as Result.Success).outputData.getString(NativeTaskScheduler.KEY_STEP_OUTPUT),
            "Nothing to forward means no key at all, not an empty string."
        )
    }

    @Test
    fun mergeFlagSet_previousOutputReachesTheWorkerInput() {
        val outcome = runWorker(
            result = WorkerResult.Success(),
            inputJson = """{"url":"https://host"}""",
            mergePreviousOutput = true,
            previousStepOutput = """{"filePath":"/tmp/x.zip"}"""
        )
        assertTrue(outcome is Result.Success)

        val received = Json.parseToJsonElement(assertNotNull(InputCapturingWorker.lastInputJson)) as JsonObject
        assertEquals("https://host", received["url"]?.jsonPrimitive?.content, "Own input must survive the merge.")
        assertEquals("/tmp/x.zip", received["filePath"]?.jsonPrimitive?.content, "Previous step's output must arrive.")
    }

    @Test
    fun mergeFlagSet_previousOutputWinsOnKeyCollision() {
        runWorker(
            result = WorkerResult.Success(),
            inputJson = """{"retries":3}""",
            mergePreviousOutput = true,
            previousStepOutput = """{"retries":1}"""
        )
        val received = Json.parseToJsonElement(assertNotNull(InputCapturingWorker.lastInputJson)) as JsonObject
        assertEquals(
            "1", received["retries"]?.jsonPrimitive?.content,
            "Android must match iOS and WorkManager's OverwritingInputMerger: previous step wins."
        )
    }

    @Test
    fun mergeFlagNotSet_previousOutputIsIgnored() {
        // Opt-in only. A step that did not ask for merging must see exactly its own input,
        // even though the payload is physically present in its inputData.
        runWorker(
            result = WorkerResult.Success(),
            inputJson = """{"url":"https://host"}""",
            mergePreviousOutput = false,
            previousStepOutput = """{"filePath":"/tmp/x.zip"}"""
        )
        val received = Json.parseToJsonElement(assertNotNull(InputCapturingWorker.lastInputJson)) as JsonObject
        assertNull(received["filePath"], "Without the opt-in flag the previous output must not leak in.")
        assertEquals("https://host", received["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun mergeFlagSet_butNoPreviousOutput_leavesOwnInputIntact() {
        // First step of a chain, or a predecessor that returned no data.
        runWorker(
            result = WorkerResult.Success(),
            inputJson = """{"url":"https://host"}""",
            mergePreviousOutput = true,
            previousStepOutput = null
        )
        val received = Json.parseToJsonElement(assertNotNull(InputCapturingWorker.lastInputJson)) as JsonObject
        assertEquals(
            "https://host", received["url"]?.jsonPrimitive?.content,
            "A missing previous output must not blank out the task's own input."
        )
    }

    @Test
    fun workerWithNullData_doesNotRepublishThePreviousStepsOutput() {
        // Guards a stale-data leak specific to how Android carries the payload.
        // Step N's inputData physically CONTAINS step N-1's output (that is the transport).
        // If a worker that produces nothing echoed its own input back out, step N+1 would be
        // handed step N-1's output as though step N had produced it — a silent, very
        // confusing data corruption in a pipeline. The output must reflect only this worker.
        val outcome = runWorker(
            result = WorkerResult.Success(data = null),
            mergePreviousOutput = true,
            previousStepOutput = """{"filePath":"/tmp/stale.zip"}"""
        )
        assertNull(
            (outcome as Result.Success).outputData.getString(NativeTaskScheduler.KEY_STEP_OUTPUT),
            "A worker returning no data must publish nothing — never re-emit the previous step's output."
        )
    }

    @Test
    fun oversizedOutput_isDroppedRatherThanFailingTheStep() {
        // WorkManager rejects Data over its limit at build time. Forwarding must degrade to
        // "no data" instead of turning a worker that genuinely succeeded into a failure.
        val huge = buildJsonObject { put("blob", "x".repeat(NativeTaskScheduler.OVERFLOW_THRESHOLD_BYTES + 1024)) }
        val outcome = runWorker(result = WorkerResult.Success(data = huge))
        assertTrue(outcome is Result.Success, "An oversized output must not fail the step. Got: $outcome")
        assertNull(
            (outcome as Result.Success).outputData.getString(NativeTaskScheduler.KEY_STEP_OUTPUT),
            "Oversized payloads are dropped, not truncated or spilled."
        )
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private fun runWorker(
        result: WorkerResult,
        inputJson: String? = """{"seed":true}""",
        deadlineMs: Long? = null,
        mergePreviousOutput: Boolean = false,
        previousStepOutput: String? = null
    ): Result {
        InputCapturingWorker.factoryHolder = AndroidWorkerFactory_Capturing(result)
        InputCapturingWorker.lastInputJson = null

        val builder = Data.Builder().putString("workerClassName", "CapturingWorker")
        if (inputJson != null) builder.putString("inputJson", inputJson)
        if (deadlineMs != null) builder.putLong(NativeTaskScheduler.KEY_DEADLINE_MS, deadlineMs)
        if (mergePreviousOutput) builder.putBoolean(NativeTaskScheduler.KEY_MERGE_PREVIOUS_OUTPUT, true)
        // Simulates what WorkManager's OverwritingInputMerger does for a chained step:
        // the prerequisite's output Data is present in this step's inputData.
        if (previousStepOutput != null) builder.putString(NativeTaskScheduler.KEY_STEP_OUTPUT, previousStepOutput)

        val worker = TestListenableWorkerBuilder<InputCapturingWorker>(context)
            .setInputData(builder.build())
            .build()
        return runBlocking { worker.doWork() }
    }
}

private class AndroidWorkerFactory_Capturing(private val result: WorkerResult) : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            InputCapturingWorker.lastInputJson = input
            return result
        }
    }
}

class InputCapturingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : BaseKmpWorker(appContext, workerParams, factoryHolder!!) {

    override val workerLogTag: String get() = "InputCapturingWorker"
    override suspend fun doWork(): Result = doWorkInternal()
    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)
            ?: return WorkerResult.Failure("not found")
        return worker.doWork(inputJson, WorkerEnvironment(
            progressListener = object : dev.brewkits.kmpworkmanager.background.domain.ProgressListener {
                override fun onProgressUpdate(progress: dev.brewkits.kmpworkmanager.background.domain.WorkerProgress) {}
            },
            isCancelled = { false }
        ))
    }

    companion object {
        @Volatile var factoryHolder: AndroidWorkerFactory? = null
        /** Input the user worker actually received — the assertion target for InputMerger. */
        @Volatile var lastInputJson: String? = null
    }
}
