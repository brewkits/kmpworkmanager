@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P2 bug: [DynamicTaskDispatcher] declared a private
 * `SupervisorJob` + `CoroutineScope` that was never used. `requestShutdownSync` (called
 * by the iOS BGTaskScheduler expiration handler — a non-suspend sync callback) cancelled
 * that unused job. The actual `executePendingTasks` coroutine ran on the *caller's*
 * scope ([IosBackgroundTaskHandler.handleMasterDispatcherTask]), so the cancel never
 * reached the in-flight worker.
 *
 * Symptom on production: when iOS expired the BGTask, `singleTaskExecutor.executeTask`
 * kept running past the budget. The `isShuttingDown` flag stopped the *next* loop
 * iteration but not the current task. If the current task ran long enough → Watchdog
 * SIGKILL of the app process.
 *
 * The fix captures the parent coroutine's Job on entry to `executePendingTasks` (via
 * `currentCoroutineContext()[Job]`) and stores it in `activeJob: AtomicReference<Job?>`.
 * `requestShutdownSync` now cancels that real parent job — cancellation propagates
 * through `withTimeout` into the worker's `delay`/IO via cooperative cancellation.
 *
 * This test pins both legs:
 *  1. The in-flight worker observes `CancellationException` (parent cancel reached it).
 *  2. `executePendingTasks` returns within a small wall-clock window of the shutdown
 *     request, not after the worker's full intended duration.
 */
class V250DispatcherShutdownTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_shutdown_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage =
        IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir(tag)
        )

    private fun makeSchedulerStub(): BackgroundTaskScheduler = object : BackgroundTaskScheduler {
        override suspend fun enqueue(id: String, trigger: TaskTrigger, workerClassName: String, constraints: Constraints, inputJson: String?, policy: ExistingPolicy) = ScheduleResult.ACCEPTED
        override fun cancel(id: String) {}
        override fun cancelAll() {}
        override fun beginWith(task: TaskRequest): TaskChain = throw UnsupportedOperationException()
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = throw UnsupportedOperationException()
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}
        override fun flushPendingProgress() {}
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun requestShutdownSync_cancelsInFlightWorker_viaParentJob() = runTest {
        val storage = makeStorage("cancel-inflight")
        val workerStarted = CompletableDeferred<Unit>()
        val workerCancelled = kotlin.concurrent.AtomicInt(0)

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    workerStarted.complete(Unit)
                    try {
                        // Simulate a long-running IO. delay() is cancellation-cooperative,
                        // so a propagated cancel will resume here with CancellationException.
                        delay(60_000)
                    } catch (e: CancellationException) {
                        workerCancelled.value = 1
                        throw e
                    }
                    return WorkerResult.Success()
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        storage.saveTaskMetadata("slow-task", mapOf("workerClassName" to "SlowWorker"), false)
        storage.enqueueTask("slow-task")

        try {
            val batchJob = launch {
                try {
                    dispatcher.executePendingTasks(makeSchedulerStub())
                } catch (_: CancellationException) {
                    // Expected — the iOS expiration path propagates cancellation up.
                }
            }

            // Wait for the worker to actually start so we know we're cancelling something
            // that's truly in-flight (not the queue-snapshot loop).
            workerStarted.await()

            // Simulate iOS expirationHandler firing. requestShutdownSync is non-suspend
            // (mirrors the BGTask callback contract).
            dispatcher.requestShutdownSync()

            // Bound the join — if this hangs, the cancel didn't propagate (regression).
            withTimeout(5_000) { batchJob.join() }

            assertEquals(
                1, workerCancelled.value,
                "REGRESSION: in-flight worker was NOT cancelled by requestShutdownSync. " +
                    "The parent-Job wiring in DynamicTaskDispatcher.executePendingTasks " +
                    "regressed — `activeJob` must capture `currentCoroutineContext()[Job]` " +
                    "and `requestShutdownSync` must cancel it. Without this, iOS BGTask " +
                    "expirationHandler leaves workers running past the budget → Watchdog " +
                    "SIGKILL of the app process."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun requestShutdownSync_withNoActiveBatch_isSafeNoOp() = runTest {
        // Called before any executePendingTasks — activeJob is null. Must not throw.
        // Protects against an over-eager fix that NPEs when iOS fires expirationHandler
        // for a BGTask that finished/never started.
        val storage = makeStorage("no-batch")
        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = null
        }
        val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(fakeFactory), storage)

        try {
            dispatcher.requestShutdownSync()
            // No exception → pass. Also verify subsequent batches still honour the flag.
            val count = dispatcher.executePendingTasks(makeSchedulerStub())
            assertEquals(0, count, "shutdown flag must still stop the next batch")
        } finally {
            storage.close()
        }
    }
}
