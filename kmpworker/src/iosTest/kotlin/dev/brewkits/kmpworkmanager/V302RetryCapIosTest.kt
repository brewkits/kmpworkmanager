@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v3.0.2 iOS side of `Constraints.maxRetries`.
 *
 * Android had `maxRetries` enforced in `BaseKmpWorker`; iOS now honors the same knob on both
 * of its execution paths:
 *
 *  - **Single one-time tasks** run through [DynamicTaskDispatcher.handleOneTimeResult], whose
 *    `Failure(shouldRetry = true)` retry loop previously always used [DynamicTaskDispatcher.DEFAULT_ATTEMPT_CAP].
 *    It now reads `Constraints.maxRetries` (stamped into task metadata) as the ceiling, with a
 *    per-result [WorkerResult.Retry.attemptCap] still taking precedence.
 *  - **Chains** run through [ChainExecutor], whose chain-level budget ([ChainProgress.maxRetries])
 *    was hard-defaulted to 3. It now derives the budget from the tasks' `Constraints.maxRetries`.
 *
 * Contract (shared with Android): `maxRetries = N` → at most `N + 1` total runs. Because iOS
 * counts differently on each path, the mapping is applied per-path:
 *  - dispatcher `effectiveCap` is "attempts including the original" → `N + 1`.
 *  - chain `ChainProgress.maxRetries` is "attempts before abandoning" → `N + 1`.
 *
 * Absent/`-1` preserves each path's prior default (dispatcher 5, chain 3) — iOS is NOT uncapped
 * on unset, unlike Android, because an uncapped BGTask retry loop would burn the app's scarce
 * background budget.
 */
class V302RetryCapIosTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_v302cap_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    private fun schedulerStub(): BackgroundTaskScheduler = object : BackgroundTaskScheduler {
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

    /** Worker factory whose worker always returns [result]. */
    private class AlwaysFactory(private val result: WorkerResult) : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
            override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = result
        }
    }

    /**
     * Factory that always returns [result] and counts how many times its worker actually ran.
     * Each chain here has a single task per step running sequentially, so a plain counter is
     * race-free (matching the ScriptedFactory precedent in V250DynamicRetryTest).
     */
    private class CountingFactory(private val result: WorkerResult) : IosWorkerFactory {
        var runs = 0
            private set
        override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
            override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                runs++
                return result
            }
        }
    }

    // --- Single-task path: DynamicTaskDispatcher honors Constraints.maxRetries from metadata ---

    @Test
    fun singleTask_maxRetriesOne_stopsAfterTwoAttempts() = runTest {
        val storage = makeStorage("single-cap1")
        try {
            val dispatcher = DynamicTaskDispatcher(
                SingleTaskExecutor(AlwaysFactory(WorkerResult.Failure("boom", shouldRetry = true))),
                storage
            )
            // maxRetries=1 → 2 total attempts (1 initial + 1 retry).
            storage.saveTaskMetadata(
                "task-cap1",
                mapOf("workerClassName" to "FlakyWorker", DynamicTaskDispatcher.META_MAX_RETRIES to "1"),
                periodic = false
            )
            storage.enqueueTask("task-cap1")

            // Attempt 1: fails → re-enqueued (budget not yet spent).
            dispatcher.executePendingTasks(schedulerStub())
            assertEquals(1, storage.getTasksQueueSize(), "After attempt 1 of 2, task must still be queued for its 1 retry.")

            // Attempt 2: fails → cap reached (2 >= 2) → dropped.
            dispatcher.executePendingTasks(schedulerStub())
            assertEquals(0, storage.getTasksQueueSize(), "After attempt 2, maxRetries=1 budget is spent — task must be dropped.")
            assertNull(
                storage.loadTaskMetadata("task-cap1", periodic = false),
                "Exhausted retry budget must drop metadata so storage doesn't accumulate dead tasks."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun singleTask_maxRetriesZero_noRetry() = runTest {
        val storage = makeStorage("single-cap0")
        try {
            val dispatcher = DynamicTaskDispatcher(
                SingleTaskExecutor(AlwaysFactory(WorkerResult.Failure("boom", shouldRetry = true))),
                storage
            )
            // maxRetries=0 → 1 total attempt, no retry even though shouldRetry=true.
            storage.saveTaskMetadata(
                "task-cap0",
                mapOf("workerClassName" to "FlakyWorker", DynamicTaskDispatcher.META_MAX_RETRIES to "0"),
                periodic = false
            )
            storage.enqueueTask("task-cap0")

            dispatcher.executePendingTasks(schedulerStub())
            assertEquals(
                0, storage.getTasksQueueSize(),
                "maxRetries=0 means the initial run is the only run — must NOT re-enqueue on Failure(shouldRetry=true)."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun singleTask_retryAttemptCap_winsOverMaxRetries() = runTest {
        val storage = makeStorage("single-capwins")
        try {
            // Worker asks for attemptCap=2 via WorkerResult.Retry; metadata says maxRetries=5.
            // The per-result attemptCap is more specific and must win: only 2 total attempts.
            val dispatcher = DynamicTaskDispatcher(
                SingleTaskExecutor(AlwaysFactory(WorkerResult.Retry("flaky", attemptCap = 2))),
                storage
            )
            storage.saveTaskMetadata(
                "task-capwins",
                mapOf("workerClassName" to "FlakyWorker", DynamicTaskDispatcher.META_MAX_RETRIES to "5"),
                periodic = false
            )
            storage.enqueueTask("task-capwins")

            dispatcher.executePendingTasks(schedulerStub())
            assertEquals(1, storage.getTasksQueueSize(), "attemptCap=2: after attempt 1, one retry remains.")

            dispatcher.executePendingTasks(schedulerStub())
            assertEquals(
                0, storage.getTasksQueueSize(),
                "Per-result attemptCap=2 must cap total attempts at 2, overriding the looser metadata maxRetries=5."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun singleTask_absentMaxRetries_usesDefaultCap() = runTest {
        val storage = makeStorage("single-default")
        try {
            val dispatcher = DynamicTaskDispatcher(
                SingleTaskExecutor(AlwaysFactory(WorkerResult.Failure("boom", shouldRetry = true))),
                storage
            )
            // No maxRetries key → falls back to DEFAULT_ATTEMPT_CAP (5 total attempts).
            storage.saveTaskMetadata("task-default", mapOf("workerClassName" to "FlakyWorker"), periodic = false)
            storage.enqueueTask("task-default")

            // Run more times than the default cap; the task must still be dropped exactly at the cap,
            // never looping forever.
            repeat(DynamicTaskDispatcher.DEFAULT_ATTEMPT_CAP + 2) {
                dispatcher.executePendingTasks(schedulerStub())
            }
            assertEquals(
                0, storage.getTasksQueueSize(),
                "Absent maxRetries must still be bounded by DEFAULT_ATTEMPT_CAP — never an infinite BGTask loop."
            )
        } finally {
            storage.close()
        }
    }

    // --- Chain path: ChainExecutor derives the chain budget from Constraints.maxRetries ---
    //
    // Each executeChainsInBatch call is one BGTask invocation. A retryable failure re-enqueues the
    // chain (bounded so it is NOT re-run in the same batch), so the retry lands on the NEXT batch —
    // we therefore drive the batch once per expected attempt and assert on the worker run count.

    private fun countingChainExecutor(storage: IosFileStorage, factory: CountingFactory): ChainExecutor =
        ChainExecutor(
            workerFactory = factory,
            taskType = BGTaskType.APP_REFRESH,
            fileStorage = storage
        )

    @Test
    fun chainNode_maxRetriesZero_runsOnceThenAbandons() = runTest {
        val storage = makeStorage("chain-cap0")
        val factory = CountingFactory(WorkerResult.Failure("transient", shouldRetry = true))
        val executor = countingChainExecutor(storage, factory)
        try {
            executor.resetShutdownState()
            val chainId = "chain-cap0"
            // maxRetries=0 → chain budget of 1 attempt → abandon on the first failure, no retry.
            storage.saveChainDefinition(
                chainId,
                listOf(listOf(TaskRequest("FlakyWorker", constraints = Constraints(maxRetries = 0))))
            )
            storage.enqueueChain(chainId)

            withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
            storage.flushNow()

            assertEquals(1, factory.runs, "maxRetries=0 must run the worker exactly once — no retry.")
            assertNull(
                storage.loadChainDefinition(chainId),
                "maxRetries=0 → budget 1 → chain abandoned (definition deleted) on the first failure."
            )
        } finally {
            executor.close()
            storage.close()
        }
    }

    @Test
    fun chainNode_maxRetriesOne_retriesOnceThenAbandons() = runTest {
        val storage = makeStorage("chain-cap1")
        val factory = CountingFactory(WorkerResult.Failure("transient", shouldRetry = true))
        val executor = countingChainExecutor(storage, factory)
        try {
            executor.resetShutdownState()
            val chainId = "chain-cap1"
            // maxRetries=1 → budget of 2 attempts (1 initial + 1 retry).
            storage.saveChainDefinition(
                chainId,
                listOf(listOf(TaskRequest("FlakyWorker", constraints = Constraints(maxRetries = 1))))
            )
            storage.enqueueChain(chainId)

            // Batch 1: first attempt fails, budget remains → chain re-enqueued for a real retry.
            withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
            storage.flushNow()
            assertEquals(1, factory.runs, "After batch 1 the worker has run once.")
            assertNotNull(
                storage.loadChainDefinition(chainId),
                "REGRESSION: retryable failure with budget left must re-enqueue the chain, not orphan it."
            )

            // Batch 2: the re-enqueued chain runs its retry, exhausts the budget, and is abandoned.
            withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
            storage.flushNow()
            assertEquals(2, factory.runs, "maxRetries=1 must actually produce a 2nd run (the retry).")
            assertNull(
                storage.loadChainDefinition(chainId),
                "After the 2nd attempt the budget is spent → chain abandoned (definition deleted)."
            )
        } finally {
            executor.close()
            storage.close()
        }
    }

    @Test
    fun chainBudget_takesMaxExplicitMaxRetriesAcrossNodes() = runTest {
        val storage = makeStorage("chain-maxnode")
        val factory = CountingFactory(WorkerResult.Failure("transient", shouldRetry = true))
        val executor = countingChainExecutor(storage, factory)
        try {
            executor.resetShutdownState()
            val chainId = "chain-maxnode"
            // Two nodes: step0 tolerates 2 retries, step1 tolerates 0. The chain-level budget is
            // resolved as the MAX explicit value (2) → 3 total attempts, so a node's maxRetries=0
            // must NOT drag the whole-chain budget down to 1. The worker always fails at step0.
            storage.saveChainDefinition(
                chainId,
                listOf(
                    listOf(TaskRequest("StepZero", constraints = Constraints(maxRetries = 2))),
                    listOf(TaskRequest("StepOne", constraints = Constraints(maxRetries = 0)))
                )
            )
            storage.enqueueChain(chainId)

            // Budget 3 → survives attempts 1 and 2, abandons on attempt 3. Had the resolver used
            // the MIN (0) instead of MAX, the chain would have been abandoned after attempt 1.
            repeat(2) {
                withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
                storage.flushNow()
                assertNotNull(
                    storage.loadChainDefinition(chainId),
                    "maxRetries=0 on step1 must not shrink the budget below step0's maxRetries=2."
                )
            }
            withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
            storage.flushNow()
            assertEquals(3, factory.runs, "max(2,0)=2 → 3 total attempts before abandoning.")
            assertNull(storage.loadChainDefinition(chainId), "Budget 3 spent → chain abandoned.")
        } finally {
            executor.close()
            storage.close()
        }
    }
}
