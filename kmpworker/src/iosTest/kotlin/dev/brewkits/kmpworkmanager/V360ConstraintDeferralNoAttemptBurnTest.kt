@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for a bug introduced alongside the standalone constraint guard
 * ([StandaloneConstraintGuard], [V360StandaloneConstraintGuardTest]): the guard's first
 * implementation routed an unmet constraint (e.g. `requiresUnmeteredNetwork` while on
 * cellular) through the SAME retry path used for a real worker failure —
 * [DynamicTaskDispatcher.handleOneTimeResult] / [IosBackgroundTaskHandler.handleOneTimeTaskResult] —
 * which increments `kmpAttemptCount` and abandons the task once the cap is exceeded.
 *
 * That conflates "never got a chance to run" with "ran and failed": a Wi-Fi-only task that
 * surfaces on cellular for 5 consecutive opportunistic wakes (e.g. a commute) would be
 * silently deleted, having never executed once — the opposite of Android's WorkManager
 * contract, where unmet constraints just leave the work enqueued indefinitely.
 *
 * Fix: a constraint deferral re-queues/re-submits WITHOUT touching the attempt counter,
 * mirroring how the backoff guard already re-queues without executing.
 */
class V360ConstraintDeferralNoAttemptBurnTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_deferral_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    private class AlwaysCellular : IosNetworkStateProvider {
        override fun isNetworkCellular(): Boolean = true
    }

    private fun makeSchedulerStub(): BackgroundTaskScheduler = object : BackgroundTaskScheduler {
        override suspend fun enqueue(id: String, trigger: TaskTrigger, workerClassName: String, constraints: Constraints, inputJson: String?, policy: ExistingPolicy, tags: Set<String>, deadlineMs: Long?) = ScheduleResult.ACCEPTED
        override fun cancel(id: String) {}
        override fun cancelAll() {}
        override fun cancelByTag(tag: String) {}
        override fun cancelByWorkerClass(workerClassName: String) {}
        override fun beginWith(task: TaskRequest): TaskChain = throw UnsupportedOperationException()
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = throw UnsupportedOperationException()
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}
        override fun flushPendingProgress() {}
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun `dynamic queue task with unmet constraint survives more wakes than the attempt cap`() = runTest {
        val storage = makeStorage("survives-cap")
        val executedCount = kotlin.concurrent.AtomicInt(0)

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    executedCount.incrementAndGet()
                    return WorkerResult.Success()
                }
            }
        }
        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage, networkStateProvider = AlwaysCellular())

        storage.saveTaskMetadata(
            "wifi-only-task",
            mapOf(
                "workerClassName" to "TestWorker",
                DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK to "true"
            ),
            periodic = false
        )
        storage.enqueueTask("wifi-only-task")

        // DEFAULT_ATTEMPT_CAP is 5 — run more dispatcher wakes than that and confirm the
        // task is neither executed (still on cellular) nor dropped.
        repeat(8) {
            dispatcher.executePendingTasks(makeSchedulerStub())
        }

        assertEquals(0, executedCount.value, "Worker must never run while the constraint stays unmet")
        assertEquals(1, storage.getTasksQueueSize(), "Task must still be queued after more wakes than the attempt cap")
        val meta = storage.loadTaskMetadata("wifi-only-task", periodic = false)
        assertNotNull(meta, "Constraint deferral must never delete task metadata")
        assertNull(
            meta[DynamicTaskDispatcher.META_ATTEMPT_COUNT],
            "A constraint deferral must not increment kmpAttemptCount — it never ran"
        )

        storage.close()
    }

    @Test
    fun `dedicated-identifier task deferred by constraint preserves attempt count at cap boundary`() = runTest {
        val storage = makeStorage("dedicated-cap")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )
        // Pre-seed at the cap boundary: a REAL retry here would be rejected (attempt 6 > cap 5).
        storage.saveTaskMetadata(
            "test-task",
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                IosBackgroundTaskHandler.META_ATTEMPT_COUNT to "5"
            ),
            periodic = false
        )
        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)!!

        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "test-task",
            meta = meta,
            result = WorkerResult.Failure("Requires unmetered network but cellular is active", shouldRetry = true),
            scheduler = scheduler,
            isConstraintDeferral = true
        )

        val updated = storage.loadTaskMetadata("test-task", periodic = false)
        assertNotNull(updated, "Constraint deferral must never drop metadata, even past the attempt cap")
        assertEquals(
            "5", updated[IosBackgroundTaskHandler.META_ATTEMPT_COUNT],
            "Constraint deferral must leave the attempt counter unchanged, not increment it"
        )

        storage.close()
    }

    /**
     * Regression net for a bug the constraint-deferral fix itself introduced: re-submitting via
     * `scheduler.enqueue(trigger = TaskTrigger.OneTime(...))` rebuilds metadata from scratch
     * ([NativeTaskScheduler.scheduleOneTimeTask]'s `buildMap`), which has no parameter for
     * `windowLatest`/tags/deadline — so without explicitly carrying them forward, a Windowed
     * task's deadline enforcement (checked in [IosBackgroundTaskHandler.handleSingleTask])
     * silently and permanently vanishes after its FIRST re-submission. Combined with removing
     * the attempt cap for constraint deferrals, this would have made a Windowed task with a
     * persistently unmet constraint retry forever with no deadline check left to stop it.
     */
    @Test
    fun `constraint deferral preserves windowLatest and tags and deadline across re-submission`() = runTest {
        val storage = makeStorage("preserve-fields")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )
        storage.saveTaskMetadata(
            "test-task",
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                "windowLatest" to "9999999999999",
                DynamicTaskDispatcher.META_TAGS to "user-123,sync",
                DynamicTaskDispatcher.META_DEADLINE_MS to "8888888888888"
                // Deliberately no kmpAttemptCount — this is the FIRST deferral, the branch
                // the pre-fix code skipped entirely (`if (currentAttempt != null)`).
            ),
            periodic = false
        )
        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)!!

        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "test-task",
            meta = meta,
            result = WorkerResult.Failure("Requires unmetered network but cellular is active", shouldRetry = true),
            scheduler = scheduler,
            isConstraintDeferral = true
        )

        // Assert via resolveTaskMetadata — the exact path handleSingleTask uses on the NEXT
        // BGTask wake to run the deadline guard. loadTaskMetadata alone wouldn't catch a
        // discrepancy if some intermediate state (e.g. a stale REPLACE artifact) made
        // resolution behave differently from a raw file read.
        val resolved = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)
        assertNotNull(resolved, "Task must still resolve after a constraint-deferral re-submit")
        assertEquals("9999999999999", resolved.rawMeta?.get("windowLatest"), "windowLatest must survive a constraint-deferral re-submit")
        assertEquals("user-123,sync", resolved.rawMeta?.get(DynamicTaskDispatcher.META_TAGS), "tags must survive a constraint-deferral re-submit")
        assertEquals("8888888888888", resolved.rawMeta?.get(DynamicTaskDispatcher.META_DEADLINE_MS), "deadline must survive a constraint-deferral re-submit")

        storage.close()
    }

    @Test
    fun `a real retry not a constraint deferral also preserves windowLatest and tags and deadline`() = runTest {
        val storage = makeStorage("preserve-fields-real-retry")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )
        storage.saveTaskMetadata(
            "test-task",
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                "windowLatest" to "9999999999999",
                DynamicTaskDispatcher.META_TAGS to "user-123",
                DynamicTaskDispatcher.META_DEADLINE_MS to "8888888888888"
            ),
            periodic = false
        )
        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)!!

        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "test-task",
            meta = meta,
            result = WorkerResult.Retry("network blip"),
            scheduler = scheduler
        )

        val resolved = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)
        assertNotNull(resolved)
        assertEquals("9999999999999", resolved.rawMeta?.get("windowLatest"), "windowLatest must survive a normal retry re-submit too")
        assertEquals("user-123", resolved.rawMeta?.get(DynamicTaskDispatcher.META_TAGS))
        assertEquals("8888888888888", resolved.rawMeta?.get(DynamicTaskDispatcher.META_DEADLINE_MS))

        storage.close()
    }

    /**
     * Regression net for [reconstructConstraintsFromMetadata] actually carrying `maxRetries`
     * through a re-submission that DOESN'T abandon the task — the abandonment case above only
     * proves the cap is read, not that it survives to bound a LATER attempt too.
     */
    @Test
    fun `dedicated-identifier retry persists custom maxRetries across a non-abandoning re-submit`() = runTest {
        val storage = makeStorage("persist-max-retries")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )
        storage.saveTaskMetadata(
            "test-task",
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                DynamicTaskDispatcher.META_MAX_RETRIES to "3"
            ),
            periodic = false
        )
        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)!!

        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "test-task",
            meta = meta,
            result = WorkerResult.Failure("transient", shouldRetry = true),
            scheduler = scheduler
        )

        val resolved = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)
        assertNotNull(resolved, "Task must survive a retry that doesn't exceed its cap")
        assertEquals(
            "3", resolved.rawMeta?.get(DynamicTaskDispatcher.META_MAX_RETRIES),
            "Custom maxRetries must survive re-submission, not silently revert to the platform default"
        )

        storage.close()
    }

    /**
     * Regression net for [reconstructConstraintsFromMetadata] carrying `maxRetries` forward
     * (see [V360StandaloneConstraintGuardTest]'s round-trip test) actually taking effect: the
     * dedicated-identifier retry path must honor a caller's custom `Constraints.maxRetries`,
     * not just the platform [IosBackgroundTaskHandler.DEFAULT_ATTEMPT_CAP].
     */
    @Test
    fun `dedicated-identifier retry honors a caller's custom maxRetries cap`() = runTest {
        val storage = makeStorage("custom-max-retries")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )
        // maxRetries = 1 means 2 total attempts. Pre-seed at attempt 2 — a 3rd attempt must be
        // rejected even though DEFAULT_ATTEMPT_CAP (5) would otherwise allow it.
        storage.saveTaskMetadata(
            "test-task",
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                IosBackgroundTaskHandler.META_ATTEMPT_COUNT to "2",
                DynamicTaskDispatcher.META_MAX_RETRIES to "1"
            ),
            periodic = false
        )
        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("test-task", storage)!!

        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "test-task",
            meta = meta,
            result = WorkerResult.Failure("still failing", shouldRetry = true),
            scheduler = scheduler
        )

        assertNull(
            storage.loadTaskMetadata("test-task", periodic = false),
            "Custom maxRetries=1 (2 total attempts) must be honored over DEFAULT_ATTEMPT_CAP=5"
        )

        storage.close()
    }
}
