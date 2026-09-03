@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression tests for the standalone-task constraint parity gaps closed in 3.4.0:
 * `requiresUnmeteredNetwork` and `SystemConstraint.REQUIRE_BATTERY_NOT_LOW`/`ALLOW_LOW_BATTERY`
 * were previously enforced only inside `ChainExecutor` (chain steps), never for a plain
 * `enqueue()` task; `Constraints.backoffPolicy`/`backoffDelayMs` had no effect on iOS at all.
 *
 * Naming convention: `VXYZBugFixesTest` per CLAUDE.md, one file per release — this is 3.4.0's.
 */
class V340StandaloneConstraintGuardTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_v360_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    private class FakeNetworkStateProvider(private val cellular: Boolean) : IosNetworkStateProvider {
        override fun isNetworkCellular(): Boolean = cellular
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

    // ==================== StandaloneConstraintGuard unit tests ====================

    @Test
    fun `violationReason returns null when rawMeta is null`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = null,
            networkStateProvider = FakeNetworkStateProvider(cellular = true)
        )
        assertNull(reason)
    }

    @Test
    fun `violationReason flags unmetered network requirement on cellular`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf(DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK to "true"),
            networkStateProvider = FakeNetworkStateProvider(cellular = true)
        )
        assertNotNull(reason)
        assertTrue(reason.contains("unmetered", ignoreCase = true))
    }

    @Test
    fun `violationReason allows unmetered-network task on wifi`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf(DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK to "true"),
            networkStateProvider = FakeNetworkStateProvider(cellular = false)
        )
        assertNull(reason)
    }

    @Test
    fun `violationReason flags requiresCharging when not charging`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf("requiresCharging" to "true"),
            networkStateProvider = FakeNetworkStateProvider(cellular = false),
            isNotCharging = { true }
        )
        assertNotNull(reason)
        assertTrue(reason.contains("charging", ignoreCase = true))
    }

    @Test
    fun `violationReason allows requiresCharging when charging`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf("requiresCharging" to "true"),
            networkStateProvider = FakeNetworkStateProvider(cellular = false),
            isNotCharging = { false }
        )
        assertNull(reason)
    }

    @Test
    fun `violationReason flags REQUIRE_BATTERY_NOT_LOW under low power mode`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf(DynamicTaskDispatcher.META_REQUIRES_BATTERY_NOT_LOW to "true"),
            networkStateProvider = FakeNetworkStateProvider(cellular = false),
            isLowPowerModeEnabled = { true }
        )
        assertNotNull(reason)
        assertTrue(reason.contains("Low Power Mode", ignoreCase = true))
    }

    @Test
    fun `violationReason ALLOW_LOW_BATTERY overrides REQUIRE_BATTERY_NOT_LOW even under low power mode`() {
        val reason = StandaloneConstraintGuard.violationReason(
            rawMeta = mapOf(
                DynamicTaskDispatcher.META_REQUIRES_BATTERY_NOT_LOW to "true",
                DynamicTaskDispatcher.META_ALLOW_LOW_BATTERY to "true"
            ),
            networkStateProvider = FakeNetworkStateProvider(cellular = false),
            isLowPowerModeEnabled = { true }
        )
        assertNull(reason, "ALLOW_LOW_BATTERY must override REQUIRE_BATTERY_NOT_LOW for this task")
    }

    // ==================== Metadata round-trip ====================

    @Test
    fun `putStandaloneConstraintMetadata and reconstructConstraintsFromMetadata round-trip`() {
        val original = Constraints(
            requiresNetwork = true,
            requiresUnmeteredNetwork = true,
            requiresCharging = true,
            isHeavyTask = true,
            backoffPolicy = BackoffPolicy.LINEAR,
            backoffDelayMs = 45_000L,
            systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW),
            maxRetries = 2
        )
        val meta = buildMap {
            put("requiresNetwork", "${original.requiresNetwork}")
            put("requiresCharging", "${original.requiresCharging}")
            put("isHeavyTask", "${original.isHeavyTask}")
            if (original.maxRetries >= 0) put(DynamicTaskDispatcher.META_MAX_RETRIES, "${original.maxRetries}")
            putStandaloneConstraintMetadata(original)
        }

        val reconstructed = reconstructConstraintsFromMetadata(meta)

        assertEquals(original.requiresNetwork, reconstructed.requiresNetwork)
        assertEquals(original.requiresUnmeteredNetwork, reconstructed.requiresUnmeteredNetwork)
        assertEquals(original.requiresCharging, reconstructed.requiresCharging)
        assertEquals(original.isHeavyTask, reconstructed.isHeavyTask)
        assertEquals(original.backoffPolicy, reconstructed.backoffPolicy)
        assertEquals(original.backoffDelayMs, reconstructed.backoffDelayMs)
        assertEquals(original.systemConstraints, reconstructed.systemConstraints)
        assertEquals(original.maxRetries, reconstructed.maxRetries)
    }

    @Test
    fun `putStandaloneConstraintMetadata writes nothing for all-default constraints`() {
        val meta = buildMap<String, String> {
            putStandaloneConstraintMetadata(Constraints())
        }
        assertTrue(meta.isEmpty(), "Default constraints must not add any metadata keys — keeps upgrades a no-op on disk")
    }

    // ==================== DynamicTaskDispatcher integration ====================

    @Test
    fun `dispatcher defers and retries a standalone task requiring unmetered network on cellular`() = runTest {
        val storage = makeStorage("unmetered-defer")
        val executedTasks = mutableListOf<String>()

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    executedTasks.add(workerClassName)
                    return WorkerResult.Success()
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(
            singleTaskExecutor = executor,
            fileStorage = storage,
            networkStateProvider = FakeNetworkStateProvider(cellular = true)
        )

        storage.saveTaskMetadata(
            "unmetered-task",
            mapOf(
                "workerClassName" to "UnmeteredWorker",
                DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK to "true"
            ),
            periodic = false
        )
        storage.enqueueTask("unmetered-task")

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(0, executedTasks.size, "Worker must not run while on cellular")
            // handleOneTimeResult re-enqueues the task for a later attempt rather than dropping it.
            assertEquals(1, storage.getTasksQueueSize(), "Task must be re-queued, not dropped")
            assertEquals(0, processedCount, "Deferred task must not count as processed")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `dispatcher runs a standalone task once network switches to wifi`() = runTest {
        val storage = makeStorage("unmetered-allow")
        val executedTasks = mutableListOf<String>()

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    executedTasks.add(workerClassName)
                    return WorkerResult.Success()
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(
            singleTaskExecutor = executor,
            fileStorage = storage,
            networkStateProvider = FakeNetworkStateProvider(cellular = false)
        )

        storage.saveTaskMetadata(
            "unmetered-task-2",
            mapOf(
                "workerClassName" to "UnmeteredWorker",
                DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK to "true"
            ),
            periodic = false
        )
        storage.enqueueTask("unmetered-task-2")

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(listOf("UnmeteredWorker"), executedTasks)
            assertEquals(1, processedCount)
            assertEquals(0, storage.getTasksQueueSize())
        } finally {
            storage.close()
        }
    }

    // ==================== Backoff timing ====================

    @Test
    fun `handleOneTimeResult stamps a future nextRetryEarliestMs using EXPONENTIAL backoff`() = runTest {
        val storage = makeStorage("backoff-exponential")
        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                    WorkerResult.Failure(message = "transient", shouldRetry = true)
            }
        }
        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(singleTaskExecutor = executor, fileStorage = storage)

        storage.saveTaskMetadata(
            "backoff-task",
            mapOf(
                "workerClassName" to "FlakyWorker",
                DynamicTaskDispatcher.META_BACKOFF_POLICY to BackoffPolicy.EXPONENTIAL.name,
                DynamicTaskDispatcher.META_BACKOFF_DELAY_MS to "10000"
            ),
            periodic = false
        )
        storage.enqueueTask("backoff-task")

        val beforeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()

        try {
            dispatcher.executePendingTasks(makeSchedulerStub())

            val meta = storage.loadTaskMetadata("backoff-task", periodic = false)
            assertNotNull(meta, "Retried task metadata must survive re-enqueue")
            val nextRetryEarliestMs = meta[DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS]?.toLongOrNull()
            assertNotNull(nextRetryEarliestMs, "A retried task must have a computed backoff floor")
            // attempt 1 -> 2: EXPONENTIAL delay = baseDelayMs * 2^(1-1) = baseDelayMs = 10_000ms
            assertTrue(
                nextRetryEarliestMs >= beforeMs + 9_000L,
                "Next retry floor ($nextRetryEarliestMs) must be roughly 10s after $beforeMs"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun `dispatcher defers a task still inside its backoff window`() = runTest {
        val storage = makeStorage("backoff-defer")
        val executedTasks = mutableListOf<String>()
        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    executedTasks.add(workerClassName)
                    return WorkerResult.Success()
                }
            }
        }
        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(singleTaskExecutor = executor, fileStorage = storage)

        val farFutureMs = (NSDate().timeIntervalSince1970 * 1000).toLong() + 60_000L
        storage.saveTaskMetadata(
            "backoff-waiting-task",
            mapOf(
                "workerClassName" to "Worker",
                DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS to "$farFutureMs"
            ),
            periodic = false
        )
        storage.enqueueTask("backoff-waiting-task")

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(0, executedTasks.size, "Worker must not run before its backoff floor")
            assertEquals(0, processedCount)
            assertEquals(1, storage.getTasksQueueSize(), "Task must remain queued for a later attempt")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `dispatcher runs a task once its backoff window has elapsed`() = runTest {
        val storage = makeStorage("backoff-elapsed")
        val executedTasks = mutableListOf<String>()
        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    executedTasks.add(workerClassName)
                    return WorkerResult.Success()
                }
            }
        }
        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(singleTaskExecutor = executor, fileStorage = storage)

        val pastMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - 1_000L
        storage.saveTaskMetadata(
            "backoff-ready-task",
            mapOf(
                "workerClassName" to "Worker",
                DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS to "$pastMs"
            ),
            periodic = false
        )
        storage.enqueueTask("backoff-ready-task")

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(listOf("Worker"), executedTasks)
            assertEquals(1, processedCount)
        } finally {
            storage.close()
        }
    }
}
