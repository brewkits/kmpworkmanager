package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression net for the fixed bug documented in [DefaultAlarmReceiver]'s own KDoc: this
 * class used to only log the alarm and finish the `PendingResult` without ever invoking
 * the scheduled worker, so every `TaskTrigger.Exact` task fired its `AlarmManager` alarm
 * on time but the work itself silently never ran. This test asserts the worker is
 * actually resolved via [KmpWorkManagerAndroid]'s registry and its `doWork` is invoked —
 * not just that no exception was thrown.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultAlarmReceiverTest {

    private class RecordingWorker(private val result: WorkerResult) : AndroidWorker {
        val invocations = AtomicInteger(0)
        var lastInput: String? = null

        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            invocations.incrementAndGet()
            lastInput = input
            return result
        }
    }

    private class TestFactory(private val workers: Map<String, RecordingWorker>) : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = workers[workerClassName]
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @After
    fun tearDown() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @Test
    fun doAlarmWork_resolvesAndInvokesTheScheduledWorker() = runBlocking {
        val worker = RecordingWorker(WorkerResult.Success())
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("RecordingWorker" to worker)))

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-1",
            workerClassName = "RecordingWorker",
            inputJson = """{"foo":"bar"}"""
        )

        assertEquals(1, worker.invocations.get(), "worker.doWork must actually run — this is the exact bug this receiver used to have")
        assertEquals("""{"foo":"bar"}""", worker.lastInput)
    }

    @Test
    fun doAlarmWork_whenWorkerNotFound_doesNotThrow() = runBlocking {
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(emptyMap()))

        // Must complete without throwing — DefaultAlarmReceiver runs inside a
        // BroadcastReceiver's coroutine scope where an uncaught exception would crash
        // the host process.
        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-missing",
            workerClassName = "NoSuchWorker",
            inputJson = null
        )
    }

    @Test
    fun doAlarmWork_whenNotInitialized_doesNotThrow() = runBlocking {
        // KmpWorkManager.shutdown() already ran in setUp() — registry is absent.
        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-uninitialized",
            workerClassName = "AnyWorker",
            inputJson = null
        )
    }

    @Test
    fun doAlarmWork_whenWorkerThrows_doesNotPropagate() = runBlocking {
        KmpWorkManager.initialize(
            context = context,
            workerFactory = object : AndroidWorkerFactory {
                override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        throw RuntimeException("boom")
                    }
                }
            }
        )

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-throws",
            workerClassName = "ThrowingWorker",
            inputJson = null
        )
        // Reaching here means the exception was caught internally, as intended.
        assertTrue(true)
    }

    // ---- I-26: exact alarms were invisible in execution history ---------------------

    /** Captures what the receiver writes, so the assertions are about the record itself. */
    private class RecordingHistoryStore : ExecutionHistoryStore {
        val records = mutableListOf<ExecutionRecord>()
        override suspend fun save(record: ExecutionRecord) { records += record }
        override suspend fun getRecords(limit: Int): List<ExecutionRecord> = records.takeLast(limit)
        override suspend fun clear() { records.clear() }
    }

    /**
     * BUG (I-26): every other execution path writes an [ExecutionRecord] — [BaseKmpWorker] on
     * Android, `ChainExecutor` and `SingleTaskExecutor` on iOS — but this receiver did not.
     * A `TaskTrigger.Exact` task ran, succeeded, and left no trace in `getExecutionHistory()`.
     *
     * The gap was easy to miss because everything else about the run *was* reported: the
     * completion event fired, telemetry fired, the log line appeared. Only the durable record
     * a host app shows its users was absent.
     */
    @Test
    fun doAlarmWork_onSuccess_writesAnExecutionRecord() = runBlocking {
        val store = RecordingHistoryStore()
        val worker = RecordingWorker(WorkerResult.Success())
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("W" to worker)))
        KmpWorkManagerRuntime.setHistoryStore(store)

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-history-ok",
            workerClassName = "W",
            inputJson = null
        )

        assertEquals(1, store.records.size, "an exact alarm must leave a trace in history")
        val record = store.records.single()
        assertEquals(ExecutionStatus.SUCCESS, record.status)
        assertEquals("exact-history-ok", record.chainId, "a standalone task records its own id")
        assertEquals(listOf("W"), record.workerClassNames)
        assertEquals(1, record.totalSteps)
        assertEquals(1, record.completedSteps)
        assertNull(record.failedStep, "a successful run has no failed step")
        assertEquals("android", record.platform)
        assertTrue(record.startedAtMs > 0, "the record must carry a real start timestamp")
        assertTrue(record.endedAtMs >= record.startedAtMs, "the run cannot end before it starts")
    }

    /** A failure has to be recorded too — an invisible failure is the worse of the two. */
    @Test
    fun doAlarmWork_onFailure_writesAFailureRecordCarryingTheError() = runBlocking {
        val store = RecordingHistoryStore()
        val worker = RecordingWorker(WorkerResult.Failure("disk full"))
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("W" to worker)))
        KmpWorkManagerRuntime.setHistoryStore(store)

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-history-fail",
            workerClassName = "W",
            inputJson = null
        )

        val record = store.records.single()
        assertEquals(ExecutionStatus.FAILURE, record.status)
        assertEquals("disk full", record.errorMessage, "the failure reason must reach history")
        assertEquals(0, record.completedSteps)
        assertEquals(0, record.failedStep)
    }

    /**
     * The failure paths that never reach the worker at all — an uninitialised library, a
     * missing worker, a worker that throws — must be recorded as well. These are precisely
     * the runs an operator most needs to see afterwards, and they are the paths where the
     * receiver returns early.
     */
    @Test
    fun doAlarmWork_recordsFailuresThatNeverReachTheWorker(): Unit = runBlocking {
        val missingWorkerStore = RecordingHistoryStore()
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(emptyMap()))
        KmpWorkManagerRuntime.setHistoryStore(missingWorkerStore)
        DefaultAlarmReceiver().doAlarmWork(context, "exact-missing", "NoSuchWorker", null)
        assertEquals(
            ExecutionStatus.FAILURE,
            missingWorkerStore.records.single().status,
            "a worker that could not be resolved must still leave a record"
        )

        val throwingStore = RecordingHistoryStore()
        KmpWorkManager.shutdown()
        KmpWorkManagerRuntime.reset()
        KmpWorkManager.initialize(
            context = context,
            workerFactory = object : AndroidWorkerFactory {
                override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                        throw RuntimeException("boom")
                }
            }
        )
        KmpWorkManagerRuntime.setHistoryStore(throwingStore)
        DefaultAlarmReceiver().doAlarmWork(context, "exact-throws", "AnyWorker", null)
        val thrown = throwingStore.records.single()
        assertEquals(ExecutionStatus.FAILURE, thrown.status)
        assertNotNull(thrown.errorMessage, "a thrown worker must record why")
        Unit
    }

    /**
     * History is diagnostic: a store that throws must not fail the run. [BaseKmpWorker] wraps
     * its own save in `runCatching` for the same reason, and this receiver runs inside a
     * BroadcastReceiver where an escaping exception crashes the host process.
     */
    @Test
    fun doAlarmWork_whenTheHistoryStoreThrows_theRunStillSucceeds() = runBlocking {
        val worker = RecordingWorker(WorkerResult.Success())
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("W" to worker)))
        KmpWorkManagerRuntime.setHistoryStore(object : ExecutionHistoryStore {
            override suspend fun save(record: ExecutionRecord) = throw RuntimeException("store is down")
            override suspend fun getRecords(limit: Int): List<ExecutionRecord> = emptyList()
            override suspend fun clear() = Unit
        })

        DefaultAlarmReceiver().doAlarmWork(context, "exact-store-down", "W", null)

        assertEquals(1, worker.invocations.get(), "the work itself must still have run")
    }

    /** No store configured is the default; the receiver must not require one. */
    @Test
    fun doAlarmWork_withNoHistoryStore_stillRunsTheWorker() = runBlocking {
        val worker = RecordingWorker(WorkerResult.Success())
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("W" to worker)))

        DefaultAlarmReceiver().doAlarmWork(context, "exact-no-store", "W", null)

        assertEquals(1, worker.invocations.get())
    }
}
