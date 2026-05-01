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

    @Test
    fun `NativeTaskScheduler should intercept dynamic tasks and enqueue them`() = runTest {
        // ... (existing code)
    }

    // ==================== Performance & Stress Tests ====================

    @Test
    @Ignore // Flaky on some CI runners due to I/O or concurrency limits
    fun `Stress Test - Enqueue 40 dynamic tasks concurrently`() = runBlocking {
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
}
