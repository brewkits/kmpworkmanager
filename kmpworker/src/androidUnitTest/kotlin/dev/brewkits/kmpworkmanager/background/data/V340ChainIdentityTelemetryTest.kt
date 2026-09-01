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
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression net for a chain-identity telemetry bug found by manually running the demo app
 * and reading its logs (per the pre-3.4.0 publish review): [BaseKmpWorker] never passed
 * `chainId`/`stepIndex` to [TelemetryHook] events, and stamped [ExecutionRecord.chainId] with
 * WorkManager's own per-request UUID instead of the ID passed to `TaskChain.withId()` — every
 * `TaskStartedEvent` logged `chain=null step=null` even for genuine chain-member tasks.
 *
 * Root cause: WorkManager's native `then()`-chaining carries no chain metadata of its own
 * (`enqueueChain()`'s `taskType = "chain"` was only ever used for a debug tag). The fix stamps
 * `NativeTaskScheduler.KEY_CHAIN_ID` / `KEY_STEP_INDEX` / `KEY_TOTAL_STEPS` into each chain
 * step's `inputData`, and `BaseKmpWorker` reads them back for both telemetry and history.
 *
 * Unlike iOS's `ChainExecutor` (one aggregate [ExecutionRecord] per chain), Android still writes
 * one record per step — see the divergence note on [ExecutionRecord]'s KDoc. This test pins the
 * per-step contract: chain-member records share the real chainId and the chain's totalSteps,
 * standalone-task records keep the pre-existing fallback behavior unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V340ChainIdentityTelemetryTest {

    private lateinit var context: Context
    private val capturedStarted = mutableListOf<TelemetryHook.TaskStartedEvent>()
    private val capturedCompleted = mutableListOf<TelemetryHook.TaskCompletedEvent>()
    private val capturedFailed = mutableListOf<TelemetryHook.TaskFailedEvent>()
    private val savedRecords = mutableListOf<ExecutionRecord>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedStarted.clear()
        capturedCompleted.clear()
        capturedFailed.clear()
        savedRecords.clear()

        KmpWorkManagerRuntime.telemetryHook = object : TelemetryHook {
            override fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) { capturedStarted += event }
            override fun onTaskCompleted(event: TelemetryHook.TaskCompletedEvent) { capturedCompleted += event }
            override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) { capturedFailed += event }
        }
        KmpWorkManagerRuntime.executionHistoryStore = object : ExecutionHistoryStore {
            override suspend fun save(record: ExecutionRecord) { savedRecords += record }
            override suspend fun getRecords(limit: Int): List<ExecutionRecord> = savedRecords.toList()
            override suspend fun clear() { savedRecords.clear() }
        }
    }

    @After
    fun tearDown() {
        KmpWorkManagerRuntime.telemetryHook = null
        KmpWorkManagerRuntime.executionHistoryStore = null
    }

    @Test
    fun chainStep_stampsRealChainIdAndStepIndex_intoTaskStartedEvent() {
        runWorker(WorkerResult.Success(), chainId = "my-real-chain-id", stepIndex = 1, totalSteps = 3)

        val started = capturedStarted.single()
        assertEquals("my-real-chain-id", started.chainId, "TaskStartedEvent.chainId must be the real chain ID, not null.")
        assertEquals(1, started.stepIndex, "TaskStartedEvent.stepIndex must be the real 0-based step index.")
    }

    @Test
    fun chainStep_stampsChainIdAndStepIndex_intoCompletedAndFailedEvents() {
        runWorker(WorkerResult.Success(), chainId = "chain-a", stepIndex = 2, totalSteps = 4)
        val completed = capturedCompleted.single()
        assertEquals("chain-a", completed.chainId)
        assertEquals(2, completed.stepIndex)

        runWorker(WorkerResult.Failure("boom", shouldRetry = false), chainId = "chain-b", stepIndex = 0, totalSteps = 2)
        val failed = capturedFailed.single()
        assertEquals("chain-b", failed.chainId)
        assertEquals(0, failed.stepIndex)
    }

    @Test
    fun chainStep_executionRecord_usesRealChainId_notWorkRequestUuid() {
        runWorker(WorkerResult.Success(), chainId = "user-supplied-chain-id", stepIndex = 0, totalSteps = 2)

        val record = savedRecords.single()
        assertEquals(
            "user-supplied-chain-id", record.chainId,
            "ExecutionRecord.chainId must match TaskChain.withId(), not a WorkManager-internal UUID."
        )
        assertEquals(2, record.totalSteps, "ExecutionRecord.totalSteps must reflect the chain's real step count.")
    }

    @Test
    fun chainStep_failure_executionRecord_failedStepMatchesRealStepIndex() {
        runWorker(WorkerResult.Failure("boom", shouldRetry = false), chainId = "chain-c", stepIndex = 2, totalSteps = 3)

        val record = savedRecords.single()
        assertEquals(ExecutionStatus.ABANDONED, record.status)
        assertEquals(2, record.failedStep, "failedStep must be the real step index that failed, not always 0.")
    }

    @Test
    fun standaloneTask_chainIdAndStepIndex_stayNull_notFalselyStamped() {
        runWorker(WorkerResult.Success(), chainId = null, stepIndex = null, totalSteps = null)

        val started = capturedStarted.single()
        assertNull(started.chainId, "A standalone (non-chain) task must never report a fabricated chainId.")
        assertNull(started.stepIndex)
    }

    @Test
    fun standaloneTask_executionRecord_fallsBackToWorkRequestId_backCompat() {
        runWorker(WorkerResult.Success(), chainId = null, stepIndex = null, totalSteps = null)

        val record = savedRecords.single()
        assertTrue(record.chainId.isNotBlank(), "Standalone tasks must keep the pre-existing auto-generated chainId fallback.")
        assertEquals(1, record.totalSteps, "Standalone tasks must keep totalSteps=1, unaffected by the chain fix.")
    }

    private fun runWorker(
        result: WorkerResult,
        chainId: String?,
        stepIndex: Int?,
        totalSteps: Int?
    ) {
        ChainIdentityTestWorker.factoryHolder = AndroidWorkerFactory_ChainIdentity(result)

        val inputBuilder = Data.Builder().putString("workerClassName", "SomeWorker")
        if (chainId != null) inputBuilder.putString(NativeTaskScheduler.KEY_CHAIN_ID, chainId)
        if (stepIndex != null) inputBuilder.putInt(NativeTaskScheduler.KEY_STEP_INDEX, stepIndex)
        if (totalSteps != null) inputBuilder.putInt(NativeTaskScheduler.KEY_TOTAL_STEPS, totalSteps)

        val worker = TestListenableWorkerBuilder<ChainIdentityTestWorker>(context)
            .setInputData(inputBuilder.build())
            .build()
        val outcome = runBlocking { worker.doWork() }
        // Sanity: the harness itself must reach the branch under test (not fail earlier for
        // unrelated reasons), otherwise the assertions above would trivially pass on empty lists.
        assertTrue(outcome is Result.Success || outcome is Result.Failure, "Unexpected worker outcome: $outcome")
    }
}

private class AndroidWorkerFactory_ChainIdentity(private val result: WorkerResult) : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = result
    }
}

class ChainIdentityTestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : BaseKmpWorker(appContext, workerParams, factoryHolder!!) {

    override val workerLogTag: String get() = "ChainIdentityTestWorker"
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
        @Volatile
        var factoryHolder: AndroidWorkerFactory? = null
    }
}
