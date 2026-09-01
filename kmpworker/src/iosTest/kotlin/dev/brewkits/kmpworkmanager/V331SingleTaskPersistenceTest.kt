package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.StoredEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression net for discussion #66 / issue #71: single (non-chained) iOS tasks never
 * persisted their completion event or execution history record. `SingleTaskExecutor`
 * emitted fire-and-forget to `TaskEventBus` only — nothing was written to `EventStore` or
 * `ExecutionHistoryStore`, so `getExecutionHistory()` only ever saw chain executions on
 * iOS, and the emission itself could be lost entirely if `cleanup()` cancelled the
 * executor's scope before the fire-and-forget coroutine ran.
 *
 * Fixed by routing through `TaskEventManager.emit()` (persists + forwards to the bus, same
 * as `ChainExecutor`) and saving an `ExecutionRecord`, both awaited inside `executeTask`'s
 * own coroutine under `NonCancellable` — mirroring Android's `BaseKmpWorker`.
 *
 * Uses in-memory fakes for `EventStore`/`ExecutionHistoryStore` rather than going through
 * `KmpWorkManager.initialize()`'s real `IosEventStore`/`IosExecutionHistoryStore` — those do
 * real `NSFileCoordinator`-backed file I/O, which is exactly the kind of thing
 * CLAUDE.md's testing conventions call out as needing per-test isolation (unique
 * `baseDirectory`) to avoid cross-test contamination and slowdowns. `SingleTaskExecutor`
 * itself only depends on the `TaskEventManager`/`KmpWorkManagerRuntime` global hooks, so
 * wiring lightweight fakes directly into those is enough to exercise the real production
 * code path end-to-end without the file-I/O overhead.
 */
class V331SingleTaskPersistenceTest {

    private class InMemoryEventStore : EventStore {
        val saved = mutableListOf<TaskCompletionEvent>()
        override suspend fun saveEvent(event: TaskCompletionEvent): String {
            saved.add(event)
            return "event-${saved.size}"
        }
        override suspend fun getUnconsumedEvents(): List<StoredEvent> = emptyList()
        override suspend fun markEventConsumed(eventId: String) {}
        override suspend fun clearOldEvents(olderThanMs: Long): Int = 0
        override suspend fun clearAll() { saved.clear() }
        override suspend fun getEventCount(): Int = saved.size
    }

    private class InMemoryExecutionHistoryStore : ExecutionHistoryStore {
        val saved = mutableListOf<ExecutionRecord>()
        override suspend fun save(record: ExecutionRecord) { saved.add(record) }
        override suspend fun getRecords(limit: Int): List<ExecutionRecord> =
            saved.sortedByDescending { it.startedAtMs }.take(limit)
        override suspend fun clear() { saved.clear() }
    }

    private class RecordingWorker(private val result: WorkerResult) : IosWorker {
        var invocations = 0
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            invocations++
            return result
        }
    }

    private class TestFactory(private val worker: IosWorker) : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? =
            if (workerClassName == "RecordingWorker") worker else null
    }

    private lateinit var eventStore: InMemoryEventStore
    private lateinit var historyStore: InMemoryExecutionHistoryStore

    @BeforeTest
    fun setUp() {
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
        eventStore = InMemoryEventStore()
        historyStore = InMemoryExecutionHistoryStore()
        TaskEventManager.initialize(eventStore)
        KmpWorkManagerRuntime.setHistoryStore(historyStore)
    }

    @AfterTest
    fun tearDown() {
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @Test
    fun `successful single task persists an event and an execution history record`() = runTest {
        val worker = RecordingWorker(WorkerResult.Success(message = "done"))
        val executor = SingleTaskExecutor(TestFactory(worker))

        val result = executor.executeTask("RecordingWorker", input = null, taskId = "task-123")

        assertTrue(result is WorkerResult.Success, "expected Success, got: $result")
        assertEquals(1, worker.invocations)

        assertEquals(1, eventStore.saved.size, "the completion event must be persisted, not just bus-forwarded")
        assertTrue(eventStore.saved.first().success)

        // The actual regression: before the fix, iOS single tasks never wrote a record at
        // all — getExecutionHistory() only ever saw chain executions on iOS.
        assertEquals(1, historyStore.saved.size, "a single task must produce exactly one history record")
        val record = historyStore.saved.first()
        assertEquals("task-123", record.chainId, "single tasks use their own id as chainId, mirroring BaseKmpWorker")
        assertEquals(ExecutionStatus.SUCCESS, record.status)
        assertEquals(1, record.totalSteps)
        assertEquals(1, record.completedSteps)
        assertNull(record.failedStep)
        assertEquals(listOf("RecordingWorker"), record.workerClassNames)
        assertEquals("ios", record.platform)
    }

    @Test
    fun `failed single task records FAILURE with the worker's error message`() = runTest {
        val worker = RecordingWorker(WorkerResult.Failure("boom"))
        val executor = SingleTaskExecutor(TestFactory(worker))

        executor.executeTask("RecordingWorker", input = null, taskId = "task-456")

        assertEquals(1, historyStore.saved.size)
        val record = historyStore.saved.first()
        assertEquals(ExecutionStatus.FAILURE, record.status)
        assertEquals(0, record.completedSteps)
        assertEquals(0, record.failedStep)
        assertEquals("boom", record.errorMessage)
    }

    @Test
    fun `missing taskId falls back to the worker class name as chainId`() = runTest {
        val worker = RecordingWorker(WorkerResult.Success())
        val executor = SingleTaskExecutor(TestFactory(worker))

        // No taskId supplied — matches a caller that hasn't threaded it through.
        executor.executeTask("RecordingWorker", input = null)

        assertEquals("RecordingWorker", historyStore.saved.first().chainId)
    }

    @Test
    fun `unregistered worker still records a FAILURE`() = runTest {
        val executor = SingleTaskExecutor(TestFactory(RecordingWorker(WorkerResult.Success())))

        val result = executor.executeTask("NoSuchWorker", input = null, taskId = "task-789")

        assertTrue(result is WorkerResult.Failure)
        assertEquals(1, historyStore.saved.size, "even a 'worker not found' outcome must be recorded")
        assertEquals(ExecutionStatus.FAILURE, historyStore.saved.first().status)
    }

    @Test
    fun `retry result is recorded as FAILURE since SingleTaskExecutor has no re-enqueue path`() = runTest {
        val worker = RecordingWorker(WorkerResult.Retry(reason = "network unavailable"))
        val executor = SingleTaskExecutor(TestFactory(worker))

        executor.executeTask("RecordingWorker", input = null, taskId = "task-retry")

        val record = historyStore.saved.first()
        assertEquals(ExecutionStatus.FAILURE, record.status)
        assertTrue(record.errorMessage?.contains("network unavailable") == true)
    }
}
