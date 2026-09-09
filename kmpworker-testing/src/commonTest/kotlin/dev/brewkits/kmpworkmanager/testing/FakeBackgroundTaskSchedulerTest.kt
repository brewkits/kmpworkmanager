package dev.brewkits.kmpworkmanager.testing

import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The first tests this published module has ever had.
 *
 * `kmpworker-testing` is shipped for other people to write their tests against, so the thing
 * that has to be true above all is that the fake **records faithfully** — an assertion helper
 * that quietly lies is worse than no helper, because the consumer's suite goes green on it.
 * These pin the recording contract and the query helpers' agreement with each other.
 */
class FakeBackgroundTaskSchedulerTest {

    private val trigger = TaskTrigger.OneTime()

    private suspend fun FakeBackgroundTaskScheduler.enqueueSample(
        id: String,
        worker: String = "SyncWorker",
    ) = enqueue(
        id = id,
        trigger = trigger,
        workerClassName = worker,
        constraints = Constraints(),
        inputJson = null,
        policy = ExistingPolicy.REPLACE,
        tags = setOf("sync"),
        deadlineMs = null,
    )

    @Test
    fun enqueue_recordsEveryArgumentInCallOrder() = runTest {
        val fake = FakeBackgroundTaskScheduler()

        fake.enqueueSample("first")
        fake.enqueueSample("second", worker = "UploadWorker")

        assertEquals(2, fake.enqueuedTasks.size)
        assertEquals(listOf("first", "second"), fake.enqueuedTasks.map { it.id })
        val recorded = fake.enqueuedTasks.first()
        assertEquals("SyncWorker", recorded.workerClassName)
        assertEquals(ExistingPolicy.REPLACE, recorded.policy)
        assertEquals(setOf("sync"), recorded.tags)
        assertNull(recorded.inputJson)
        assertNull(recorded.deadlineMs)
    }

    @Test
    fun enqueue_returnsTheConfiguredDefaultResult() = runTest {
        assertEquals(ScheduleResult.ACCEPTED, FakeBackgroundTaskScheduler().enqueueSample("a"))
        assertEquals(
            ScheduleResult.REJECTED_OS_POLICY,
            FakeBackgroundTaskScheduler(defaultResult = ScheduleResult.REJECTED_OS_POLICY).enqueueSample("a"),
        )
    }

    @Test
    fun cancellationCalls_areRecordedSeparatelyPerKind() = runTest {
        val fake = FakeBackgroundTaskScheduler()
        fake.enqueueSample("task-1")

        fake.cancel("task-1")
        fake.cancelByTag("sync")
        fake.cancelByWorkerClass("SyncWorker")
        fake.flushPendingProgress()
        fake.flushPendingProgress()

        assertEquals(listOf("task-1"), fake.cancelledIds)
        assertEquals(listOf("sync"), fake.cancelledTags)
        assertEquals(listOf("SyncWorker"), fake.cancelledWorkerClasses)
        assertEquals(2, fake.flushCount)
        assertFalse(fake.cancelAllCalled)
        assertTrue(fake.hasCancelled("task-1"))
        assertFalse(fake.isPending("task-1"))
    }

    /**
     * [FakeBackgroundTaskScheduler.isPending] and
     * [FakeBackgroundTaskScheduler.pendingTaskCount] answer the same question and must not
     * disagree. They did: `cancelAll()` sets a flag without touching `cancelledIds`, so
     * `pendingTaskCount()` correctly returned 0 while `isPending(id)` still said `true` for
     * every task. A consumer asserting "nothing is pending after cancelAll" the natural way —
     * per id — got a green test on a false answer.
     */
    @Test
    fun cancelAll_makesEveryTaskNonPending_byBothQueries() = runTest {
        val fake = FakeBackgroundTaskScheduler()
        fake.enqueueSample("a")
        fake.enqueueSample("b")
        assertEquals(2, fake.pendingTaskCount())
        assertTrue(fake.isPending("a"))

        fake.cancelAll()

        assertTrue(fake.cancelAllCalled)
        assertEquals(0, fake.pendingTaskCount())
        assertFalse(fake.isPending("a"), "cancelAll must clear per-id pending state too")
        assertFalse(fake.isPending("b"))
    }

    @Test
    fun pendingTaskCount_countsOnlyTasksNotIndividuallyCancelled() = runTest {
        val fake = FakeBackgroundTaskScheduler()
        fake.enqueueSample("a")
        fake.enqueueSample("b")
        fake.enqueueSample("c")

        fake.cancel("b")

        assertEquals(2, fake.pendingTaskCount())
        assertTrue(fake.isPending("a"))
        assertFalse(fake.isPending("b"))
        assertTrue(fake.hasEnqueued("SyncWorker"))
        assertFalse(fake.hasEnqueued("NoSuchWorker"))
    }

    @Test
    fun beginWith_producesAChainThisSchedulerRecordsOnEnqueue() = runTest {
        val fake = FakeBackgroundTaskScheduler()

        val chain = fake.beginWith(TaskRequest(workerClassName = "StepOne"))
            .then(TaskRequest(workerClassName = "StepTwo"))
        fake.enqueueChain(chain, id = "chain-1", policy = ExistingPolicy.KEEP)

        assertEquals(1, fake.enqueuedChains.size)
        val recorded = fake.enqueuedChains.single()
        assertEquals("chain-1", recorded.id)
        assertEquals(ExistingPolicy.KEEP, recorded.policy)
        // TaskChain.getSteps() is internal to :kmpworker, so a consumer of this module cannot
        // read the steps back either — assert what they CAN observe: the exact chain instance
        // they built is the one handed to enqueueChain, not a copy or a rebuilt one.
        assertSame(chain, recorded.chain)
    }

    @Test
    fun reset_clearsEveryPieceOfRecordedState() = runTest {
        val fake = FakeBackgroundTaskScheduler()
        fake.enqueueSample("a")
        fake.enqueueChain(fake.beginWith(TaskRequest(workerClassName = "StepOne")), null, ExistingPolicy.REPLACE)
        fake.cancel("a")
        fake.cancelByTag("sync")
        fake.cancelByWorkerClass("SyncWorker")
        fake.flushPendingProgress()
        fake.cancelAll()

        fake.reset()

        assertTrue(fake.enqueuedTasks.isEmpty())
        assertTrue(fake.enqueuedChains.isEmpty())
        assertTrue(fake.cancelledIds.isEmpty())
        assertTrue(fake.cancelledTags.isEmpty())
        assertTrue(fake.cancelledWorkerClasses.isEmpty())
        assertEquals(0, fake.flushCount)
        assertFalse(fake.cancelAllCalled, "a stale cancelAll flag would make every later assertion wrong")
        assertEquals(0, fake.pendingTaskCount())
    }

    @Test
    fun executionHistory_isEmptyAndClearingItIsSafe() = runTest {
        val fake = FakeBackgroundTaskScheduler()
        assertTrue(fake.getExecutionHistory(10).isEmpty())
        fake.clearExecutionHistory()
        assertTrue(fake.getExecutionHistory(10).isEmpty())
    }
}
