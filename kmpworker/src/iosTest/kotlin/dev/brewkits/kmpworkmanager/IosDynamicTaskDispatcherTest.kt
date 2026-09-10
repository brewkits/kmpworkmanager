@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Unit and Integration tests for the iOS Dynamic Task Dispatcher.
 *
 * Each test creates its own IosFileStorage with a unique directory to avoid the
 * shared-field race that occurs when the Kotlin/Native test runner executes tests
 * concurrently across multiple threads.
 */
class IosDynamicTaskDispatcherTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_dynamic_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage {
        return IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir(tag)
        )
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

    // ==================== IosFileStorage Tasks Queue Tests ====================

    @Test
    fun `tasks queue should work in FIFO order`() = runTest {
        val storage = makeStorage("fifo")
        try {
            storage.enqueueTask("task-1")
            storage.enqueueTask("task-2")
            storage.enqueueTask("task-3")

            assertEquals(3, storage.getTasksQueueSize())

            assertEquals("task-1", storage.dequeueTask())
            assertEquals("task-2", storage.dequeueTask())
            assertEquals("task-3", storage.dequeueTask())
            assertEquals(0, storage.getTasksQueueSize())
        } finally {
            storage.close()
        }
    }

    @Test
    fun `dequeueTask from empty queue should return null`() = runTest {
        val storage = makeStorage("empty-dequeue")
        try {
            assertNull(storage.dequeueTask())
        } finally {
            storage.close()
        }
    }

    // ==================== DynamicTaskDispatcher Tests ====================

    @Test
    fun `DynamicTaskDispatcher should process all tasks in queue`() = runTest {
        val storage = makeStorage("dispatcher-process")
        val executedTasks = mutableListOf<String>()

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                return object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        executedTasks.add(workerClassName)
                        return WorkerResult.Success()
                    }
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        storage.saveTaskMetadata("task-a", mapOf("workerClassName" to "WorkerA"), false)
        storage.enqueueTask("task-a")
        storage.saveTaskMetadata("task-b", mapOf("workerClassName" to "WorkerB"), false)
        storage.enqueueTask("task-b")

        val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

        try {
            assertEquals(2, processedCount)
            assertEquals(listOf("WorkerA", "WorkerB"), executedTasks)
            assertEquals(0, storage.getTasksQueueSize())
        } finally {
            storage.close()
        }
    }

    @Test
    fun `DynamicTaskDispatcher should stop on shutdown request`() = runTest {
        val storage = makeStorage("dispatcher-shutdown")

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                return object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        return WorkerResult.Success()
                    }
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        storage.saveTaskMetadata("task-1", mapOf("workerClassName" to "Worker1"), false)
        storage.enqueueTask("task-1")
        storage.saveTaskMetadata("task-2", mapOf("workerClassName" to "Worker2"), false)
        storage.enqueueTask("task-2")

        dispatcher.requestShutdownSync()

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())
            assertEquals(0, processedCount, "No tasks should be processed after shutdown request")
            assertEquals(2, storage.getTasksQueueSize(), "Tasks should remain in queue")
        } finally {
            storage.close()
        }
    }

    // ==================== NativeTaskScheduler Integration Test ====================

    /**
     * The interception rule the whole dynamic-task feature rests on: an id that is **not** in
     * Info.plist must not be submitted to BGTaskScheduler under its own identifier — it goes
     * onto the internal queue for the Master Dispatcher to run instead, with its metadata
     * persisted so the dispatcher can resolve the worker after a process restart.
     *
     * This test had a body of `// ... (existing code)` from the commit that introduced the
     * feature (ca9db14) until v3.5.0. It compiled, ran, asserted nothing and reported as
     * passing — worse than `@Ignore`, which at least announces itself, and it counted toward
     * the suite's totals the whole time.
     */
    @Test
    fun `NativeTaskScheduler should intercept dynamic tasks and enqueue them`() = runTest {
        val storage = makeStorage("intercept")
        val dedicatedId = "task-with-dedicated-identifier"
        val scheduler = NativeTaskScheduler(
            // Literal rather than NativeTaskScheduler.MASTER_DISPATCHER_IDENTIFIER: the
            // companion is private. The stress test in this file uses the same literal.
            additionalPermittedTaskIds = setOf("kmp_master_dispatcher_task", dedicatedId),
            fileStorage = storage
        )

        try {
            val dynamicResult = scheduler.enqueue(
                id = "task-without-identifier",
                trigger = TaskTrigger.OneTime(0L),
                workerClassName = "SyncWorker"
            )

            assertEquals(ScheduleResult.ACCEPTED, dynamicResult, "a dynamic task must be accepted")
            assertTrue(
                storage.isTaskInDynamicQueue("task-without-identifier"),
                "an id absent from Info.plist must be intercepted onto the dynamic queue"
            )
            assertEquals(1, storage.getTasksQueueSize())

            // Metadata has to be persisted too, or the dispatcher cannot resolve the worker
            // when it later dequeues the id.
            assertEquals(
                "SyncWorker",
                storage.loadTaskMetadata("task-without-identifier", periodic = false)
                    ?.get("workerClassName"),
                "the dispatcher resolves the worker from metadata after a process restart"
            )

            // The other half of the rule, and a distinction that is easy to "fix" wrongly:
            // interception keys on `infoPlistTaskIds` ALONE, not on `permittedTaskIds`.
            // `additionalPermittedTaskIds` only relaxes the library's own validation — it
            // cannot grant a dedicated BGTask, because BGTaskScheduler will not register an
            // identifier that is absent from Info.plist. So an id passed there is still
            // intercepted, which is correct: the dynamic queue is the only way it can ever
            // run. Widening line 853 to `permittedTaskIds` would submit a BGTask under an
            // unregistered identifier and the task would simply never fire.
            scheduler.enqueue(
                id = dedicatedId,
                trigger = TaskTrigger.OneTime(0L),
                workerClassName = "SyncWorker"
            )

            assertTrue(
                storage.isTaskInDynamicQueue(dedicatedId),
                "additionalPermittedTaskIds relaxes validation only; without a real Info.plist " +
                    "entry the task must still be intercepted, or it could never run"
            )
            assertEquals(
                2,
                storage.getTasksQueueSize(),
                "both tasks are dynamic in a test environment, which has no Info.plist"
            )
        } finally {
            storage.close()
        }
    }

    // ==================== Performance & Stress Tests ====================

    @Test
    fun `Stress Test - Enqueue 40 dynamic tasks concurrently`() = runBlocking {
        // Was @Ignore'd as "flaky on some CI runners due to I/O or concurrency limits".
        // Gated instead of deleted from the suite: it still compiles against the current API,
        // it says so when it skips, and KMP_RUN_STRESS_TESTS=1 brings it back. See StressTests.
        if (StressTests.skip("Enqueue 40 dynamic tasks concurrently")) return@runBlocking
        val storage = makeStorage("stress-40")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("kmp_master_dispatcher_task"),
            fileStorage = storage
        )

        val taskCount = 40
        val results = mutableListOf<ScheduleResult>()
        val resultsMutex = kotlinx.coroutines.sync.Mutex()

        coroutineScope {
            repeat(taskCount) { i ->
                launch {
                    val res = scheduler.enqueue(
                        id = "stress-task-$i",
                        trigger = TaskTrigger.OneTime(0),
                        workerClassName = "StressWorker",
                        constraints = Constraints(),
                        inputJson = """{"index": $i}""",
                        policy = ExistingPolicy.KEEP
                    )
                    resultsMutex.withLock {
                        results.add(res)
                    }
                    if (res != ScheduleResult.ACCEPTED) {
                        Logger.e(LogTags.SCHEDULER, "Task stress-task-$i REJECTED: $res")
                    }
                }
            }
        }

        try {
            val acceptedCount = results.count { it == ScheduleResult.ACCEPTED }
            assertEquals(taskCount, acceptedCount, "All $taskCount tasks must be ACCEPTED (Actual: $acceptedCount, Results: $results)")
            assertEquals(taskCount, storage.getTasksQueueSize(), "All $taskCount tasks must be enqueued without loss")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `Performance Test - Dispatcher should process 100 tasks efficiently`() = runTest {
        val storage = makeStorage("perf-100")
        val taskCount = 100
        val processedCount = AtomicInt(0)

        val fakeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                return object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        processedCount.incrementAndGet()
                        return WorkerResult.Success()
                    }
                }
            }
        }

        val executor = SingleTaskExecutor(fakeFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        repeat(taskCount) { i ->
            storage.saveTaskMetadata("task-$i", mapOf("workerClassName" to "Worker"), false)
            storage.enqueueTask("task-$i")
        }

        val startTime = NSDate().timeIntervalSince1970
        val count = dispatcher.executePendingTasks(makeSchedulerStub())
        val endTime = NSDate().timeIntervalSince1970

        try {
            assertEquals(taskCount, count)
            assertEquals(taskCount, processedCount.value)
            println("Performance: Processed $taskCount tasks in ${endTime - startTime} seconds")
        } finally {
            storage.close()
        }
    }

    // ==================== Unexpected factory exception handling (#see CHANGELOG) ====================
    //
    // SingleTaskExecutor only catches IllegalArgumentException around
    // workerFactory.createWorker() (its documented contract). A host app's factory can throw
    // something else entirely — these tests pin that such an exception is treated as a
    // retryable failure (routed through handleOneTimeResult / reschedulePeriodicTask) instead
    // of being swallowed by a bare log line that silently orphans a one-time task or
    // permanently stops a periodic task's schedule.

    @Test
    fun `one-time task survives an unexpected exception from the worker factory`() = runTest {
        val storage = makeStorage("factory-throws-onetime")

        val throwingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker {
                throw NullPointerException("DI container not initialized")
            }
        }

        val executor = SingleTaskExecutor(throwingFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        storage.saveTaskMetadata("task-npe", mapOf("workerClassName" to "Worker"), false)
        storage.enqueueTask("task-npe")

        try {
            val processedCount = dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(1, processedCount, "the task must still count as processed (handled), not silently dropped")
            assertNotNull(
                storage.loadTaskMetadata("task-npe", periodic = false),
                "metadata must survive for a retry — the old behavior orphaned it until the 7-day sweep"
            )
            assertEquals(1, storage.getTasksQueueSize(), "the task must be re-enqueued for retry, not lost")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `periodic task is still rescheduled after an unexpected exception from the worker factory`() = runTest {
        val storage = makeStorage("factory-throws-periodic")
        var enqueueCallCount = 0
        val reschedulingScheduler = object : BackgroundTaskScheduler {
            override suspend fun enqueue(id: String, trigger: TaskTrigger, workerClassName: String, constraints: Constraints, inputJson: String?, policy: ExistingPolicy, tags: Set<String>, deadlineMs: Long?): ScheduleResult {
                enqueueCallCount++
                return ScheduleResult.ACCEPTED
            }
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

        val throwingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker {
                throw IllegalStateException("host app misconfiguration")
            }
        }

        val executor = SingleTaskExecutor(throwingFactory)
        val dispatcher = DynamicTaskDispatcher(executor, storage)

        storage.saveTaskMetadata(
            "periodic-npe",
            mapOf("workerClassName" to "Worker", "intervalMs" to "3600000", "isPeriodic" to "true"),
            periodic = true
        )
        storage.enqueueTask("periodic-npe")

        try {
            val processedCount = dispatcher.executePendingTasks(reschedulingScheduler)

            assertEquals(1, processedCount)
            assertEquals(
                1,
                enqueueCallCount,
                "reschedulePeriodicTask must still run — the old behavior silently and permanently stopped the periodic schedule"
            )
        } finally {
            storage.close()
        }
    }
}
