package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressBus
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressEvent
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the iOS-side fixes shipped in v3.5.0.
 *
 * Per the project convention (see CLAUDE.md — "Testing Conventions"), this file is tied to
 * one release and is never edited afterwards; a later release gets its own `V###` file.
 */
@OptIn(ExperimentalForeignApi::class)
class V350BugFixesIosTest {

    // ── crashAttemptCount: forward progress must clear the poison-pill counter ──────────
    //
    // Before v3.5.0 `crashAttemptCount` was incremented at the top of EVERY
    // `ChainExecutor.executeChain()` invocation and reset nowhere in the codebase, so it
    // counted clean pre-emptions (BGTask expiry, batch timeout, cancellation) exactly like
    // process kills. A chain long enough to need a 5th BGTask window — entirely normal, since
    // BGProcessingTask gives ~300s and a chain may hold dozens of steps — therefore tripped
    // `isPoisonPill()` on that window and was deleted along with every step it had already
    // completed. These tests pin the corrected semantics: the counter means "consecutive
    // invocations that finished no step", not "invocations, ever".

    private fun fresh(totalSteps: Int = 20) =
        ChainProgress(chainId = "chain-v350", totalSteps = totalSteps)

    @Test
    fun completingAStep_resetsCrashAttemptCount() {
        val afterFourInterruptedWindows = fresh()
            .withCrashAttempt()
            .withCrashAttempt()
            .withCrashAttempt()
            .withCrashAttempt()
        assertEquals(4, afterFourInterruptedWindows.crashAttemptCount)

        val afterProgress = afterFourInterruptedWindows.withCompletedStep(0)

        assertEquals(
            0,
            afterProgress.crashAttemptCount,
            "finishing a step proves the chain is making progress, so the crash counter must clear",
        )
        assertFalse(afterProgress.isPoisonPill())
    }

    @Test
    fun longChain_makingProgressEachWindow_isNeverQuarantined() {
        // Simulates the real failure: 12 BGTask windows, each of which starts the chain
        // (+1 crash attempt), completes one step, and is then pre-empted by the OS.
        // MAX_CRASH_ATTEMPTS is 5, so pre-fix this chain died on window 5.
        var progress = fresh(totalSteps = 12)
        repeat(12) { window ->
            progress = progress.withCrashAttempt()
            assertFalse(
                progress.isPoisonPill(),
                "window $window: a chain completing a step per window must never be quarantined",
            )
            progress = progress.withCompletedStep(window)
        }
        assertTrue(progress.isComplete())
        assertEquals(12, progress.completedSteps.size, "no completed step may be lost")
    }

    @Test
    fun chainThatNeverCompletesAStep_stillTripsPoisonPill() {
        // The guard must keep its teeth: a chain dying inside the same step never reaches
        // withCompletedStep, so nothing resets the counter.
        var progress = fresh()
        repeat(ChainProgress.MAX_CRASH_ATTEMPTS - 1) {
            progress = progress.withCrashAttempt()
            assertFalse(progress.isPoisonPill())
        }
        progress = progress.withCrashAttempt()
        assertTrue(
            progress.isPoisonPill(),
            "a genuinely crash-looping chain must still be quarantined at MAX_CRASH_ATTEMPTS",
        )
    }

    @Test
    fun crashesAfterProgress_countFromZeroAgain() {
        // Progress at step 0, then the chain starts crash-looping inside step 1. The counter
        // restarts from the reset, so it takes a further MAX_CRASH_ATTEMPTS to quarantine —
        // it does not inherit the pre-progress count.
        var progress = fresh().withCrashAttempt().withCrashAttempt().withCompletedStep(0)
        assertEquals(0, progress.crashAttemptCount)

        repeat(ChainProgress.MAX_CRASH_ATTEMPTS - 1) {
            progress = progress.withCrashAttempt()
            assertFalse(progress.isPoisonPill())
        }
        progress = progress.withCrashAttempt()
        assertTrue(progress.isPoisonPill())
    }

    @Test
    fun repeatedCompletionOfSameStep_doesNotResetCounter() {
        // withCompletedStep short-circuits when the step is already recorded, so a duplicate
        // call must NOT be usable as a way to keep clearing the crash counter forever.
        val progress = fresh().withCompletedStep(0).withCrashAttempt().withCrashAttempt()
        assertEquals(2, progress.crashAttemptCount)

        val duplicate = progress.withCompletedStep(0)

        assertEquals(
            2,
            duplicate.crashAttemptCount,
            "re-completing an already-completed step is not progress and must not reset the counter",
        )
    }

    // ── #10 / #16 — SingleTaskExecutor: no close(), and progress keyed by worker class ──

    private class CloseTrackingWorker(
        private val result: WorkerResult = WorkerResult.Success(message = "done"),
        private val workDelayMs: Long = 0L,
        private val reportProgress: Boolean = false,
    ) : IosWorker {
        var closed = false
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            if (reportProgress) {
                env.progressListener?.onProgressUpdate(
                    WorkerProgress(progress = 50, message = "halfway"),
                )
            }
            if (workDelayMs > 0) delay(workDelayMs)
            return result
        }

        override fun close() {
            closed = true
        }
    }

    private class SingleWorkerFactory(private val worker: IosWorker) : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? = worker
    }

    /**
     * Worker.close() was never called anywhere in SingleTaskExecutor, so every non-chained
     * task leaked whatever its worker held — an HttpClient, an open file handle, a native
     * resource — for the lifetime of the process. ChainExecutor has had the equivalent
     * `finally` since 3.3.
     */
    @Test
    fun singleTask_closesTheWorker_onTheSuccessPath() = runTest {
        val worker = CloseTrackingWorker()
        SingleTaskExecutor(SingleWorkerFactory(worker))
            .executeTask("CloseTrackingWorker", input = null, taskId = "v350-close-ok")

        assertTrue(worker.closed, "a finished worker must be closed")
    }

    /**
     * The timeout path matters more than the happy path: a worker killed mid-flight is
     * exactly the one that did not get to release anything itself.
     */
    @Test
    fun singleTask_closesTheWorker_evenWhenItTimesOut() = runTest {
        val worker = CloseTrackingWorker(workDelayMs = 10_000L)
        val result = SingleTaskExecutor(SingleWorkerFactory(worker))
            .executeTask("CloseTrackingWorker", input = null, timeoutMs = 50L, taskId = "v350-close-timeout")

        assertTrue(result is WorkerResult.Failure, "expected a timeout Failure, got: $result")
        assertTrue(worker.closed, "a timed-out worker must still be closed")
    }

    /**
     * Progress was emitted with `taskId = workerClassName`, so two concurrent tasks of the
     * same worker class were indistinguishable to any consumer — and, because
     * TaskProgressBus throttles per taskId, one task's updates suppressed the other's.
     * The ExecutionRecord for the same run was already keyed by the real task id, so the
     * two views of one task disagreed.
     */
    @Test
    fun singleTaskProgress_isKeyedByTaskId_notByWorkerClass() = runTest {
        val taskId = "v350-progress-${Random.nextInt()}"
        val received = CompletableDeferred<TaskProgressEvent>()
        val collectorScope = CoroutineScope(Dispatchers.Default)
        val collector = collectorScope.launch {
            TaskProgressBus.events.collect { event ->
                if (event.taskId == taskId && !received.isCompleted) received.complete(event)
            }
        }
        try {
            // Real time, not the test scheduler's virtual clock: the collector subscribes on
            // Dispatchers.Default and the executor emits from its own scope there too.
            withContext(Dispatchers.Default) { delay(100) }

            SingleTaskExecutor(SingleWorkerFactory(CloseTrackingWorker(reportProgress = true)))
                .executeTask("CloseTrackingWorker", input = null, taskId = taskId)

            val event = withContext(Dispatchers.Default) { withTimeout(5_000) { received.await() } }
            assertEquals(
                taskId,
                event.taskId,
                "progress must be addressed by the task id the caller enqueued, not the worker class",
            )
            assertEquals("CloseTrackingWorker", event.taskName)
        } finally {
            collector.cancel()
            collectorScope.cancel()
        }
    }

    // ── Cancellation during executeChain's prologue silently lost the chain ────────────
    //
    // executeNextChainFromQueue dequeues FIRST, then calls executeChain. Every re-enqueue
    // inside executeChain lives in the inner try that wraps step execution, so a
    // cancellation anywhere in the prologue — loading the definition, reading the
    // windowed-deadline metadata, loading/saving progress, all of which suspend on file
    // coordination — left the chain in neither the queue nor in flight. Its definition
    // stayed on disk with nothing left to pick it up. A BGTask expiring during the prologue
    // is not an exotic case; it is how an iOS background window normally ends.

    private class NeverRunsFactory : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
            override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                throw AssertionError("no step may run: the chain must be cancelled in the prologue")
        }
    }

    @Test
    fun chainCancelledDuringThePrologue_isPutBackOnTheQueue() = runBlocking {
        // runBlocking, not runTest: the executor dispatches file I/O onto real GCD threads,
        // and runTest's virtual clock never advances for those.
        val dir = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}v350_prologue_${NSDate().timeIntervalSince1970()}_${Random.nextInt()}",
        )
        NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
        val storage = IosFileStorage(baseDirectory = dir)
        val executor = ChainExecutor(NeverRunsFactory(), fileStorage = storage)
        try {
            val chainId = "v350-prologue-cancel"
            storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest(workerClassName = "NeverRuns"))))
            storage.enqueueChain(chainId)
            assertEquals(1, storage.getQueueSize(), "precondition: the chain is queued")

            // Hold the prologue open at its first suspension point so the cancellation
            // deterministically lands after the dequeue and before any step runs.
            storage.testLoadChainDefinitionDelayMs = 5_000

            val job = launch(Dispatchers.Default) { executor.executeNextChainFromQueue() }

            withTimeout(10_000) {
                while (storage.getQueueSize() != 0) delay(20)
            }
            job.cancelAndJoin()

            assertEquals(
                1,
                storage.getQueueSize(),
                "a chain cancelled between dequeue and step execution must go back on the queue",
            )
            assertTrue(storage.chainExists(chainId), "its definition must still be on disk to resume from")
        } finally {
            storage.testLoadChainDefinitionDelayMs = 0L
            executor.close()
            storage.close()
            NSFileManager.defaultManager.removeItemAtURL(dir, null)
        }
    }

    /**
     * BUG: every iOS listing skipped tasks and chains whose id starts with a dot.
     *
     * `listJsonFileIds` enumerated with `NSDirectoryEnumerationSkipsHiddenFiles`. The path
     * encoder leaves a leading `.` alone, so an id like `.internal.sync` is stored as
     * `.internal.sync.json` — which the OS classifies as hidden. The record was on disk and
     * `loadTaskMetadata(id)` returned it, but it was absent from `listTaskIds`,
     * `listOneTimeTaskIdsDecoded`, `listPeriodicTaskIds` and `listChainDefinitionIds`, and so
     * from `queryTasks`, from `computeIosTaskState`, and from the catch-up scan for missed
     * exact alarms. `findTaskIdsByWorkerOrTag` enumerates through `contentsOfDirectoryAtPath`
     * instead and still saw them, so `cancelByTag` and `queryTasks` disagreed about which
     * tasks existed.
     *
     * FIX: the option is gone. This directory holds only the library's own records, so there
     * is no user "hidden file" to respect, and the `.json` suffix filter already excludes
     * strays such as `.DS_Store`.
     */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun dotPrefixedIdsAreVisibleToEveryListing() = runTest {
        val dir = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}v350_dot_listing_${NSDate().timeIntervalSince1970()}_${Random.nextInt()}",
        )
        NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
        val storage = IosFileStorage(baseDirectory = dir)
        try {
            val dotted = ".internal.sync"
            val ordinary = "ordinary-task"

            storage.saveTaskMetadata(dotted, mapOf("workerClassName" to "W"), periodic = false)
            storage.saveTaskMetadata(ordinary, mapOf("workerClassName" to "W"), periodic = false)
            storage.saveChainDefinition(".hidden-chain", listOf(listOf(TaskRequest("W"))))

            assertTrue(
                storage.listOneTimeTaskIdsDecoded().toSet().contains(dotted),
                "a dot-prefixed task id must be listed, not treated as a hidden file",
            )
            assertTrue(
                storage.listOneTimeTaskIdsDecoded().toSet().contains(ordinary),
                "ordinary ids must keep working",
            )
            assertTrue(
                storage.listChainDefinitionIds().toSet().contains(".hidden-chain"),
                "a dot-prefixed chain id must be listed too",
            )

            // The inconsistency this bug produced: the tag scan and the listing must agree.
            assertEquals(
                listOf(dotted to false),
                storage.findTaskIdsByWorkerOrTag(workerClassName = "W")
                    .filter { it.first == dotted },
                "the tag scan already saw dot-prefixed ids; the listing must now agree",
            )
        } finally {
            storage.close()
            NSFileManager.defaultManager.removeItemAtURL(dir, null)
        }
    }
}
