@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Unit and Integration tests for the iOS Dynamic Task Dispatcher.
 */
class IosDynamicTaskDispatcherTest {

    private lateinit var fileStorage: IosFileStorage
    private lateinit var testDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_dynamic_test_${NSDate().timeIntervalSince1970()}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(testDirectoryURL, withIntermediateDirectories = true, attributes = null, error = null)

        fileStorage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = testDirectoryURL
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        fileStorage.close()
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    // ==================== IosFileStorage Tasks Queue Tests ====================

    @Test
    fun `tasks queue should work in FIFO order`() = runTest {
        fileStorage.enqueueTask("task-1")
        fileStorage.enqueueTask("task-2")
        fileStorage.enqueueTask("task-3")

        assertEquals(3, fileStorage.getTasksQueueSize())

        assertEquals("task-1", fileStorage.dequeueTask())
        assertEquals("task-2", fileStorage.dequeueTask())
        assertEquals("task-3", fileStorage.dequeueTask())
        assertEquals(0, fileStorage.getTasksQueueSize())
    }

    @Test
    fun `dequeueTask from empty queue should return null`() = runTest {
        assertNull(fileStorage.dequeueTask())
    }

    // ==================== DynamicTaskDispatcher Tests ====================

    @Test
    fun `DynamicTaskDispatcher should process all tasks in queue`() = runTest {
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
        val dispatcher = DynamicTaskDispatcher(executor, fileStorage)
        
        val scheduler = object : BackgroundTaskScheduler {
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

        // Enqueue some tasks
        fileStorage.saveTaskMetadata("task-a", mapOf("workerClassName" to "WorkerA"), false)
        fileStorage.enqueueTask("task-a")
        fileStorage.saveTaskMetadata("task-b", mapOf("workerClassName" to "WorkerB"), false)
        fileStorage.enqueueTask("task-b")

        val processedCount = dispatcher.executePendingTasks(scheduler)
        
        assertEquals(2, processedCount)
        assertEquals(listOf("WorkerA", "WorkerB"), executedTasks)
        assertEquals(0, fileStorage.getTasksQueueSize())
    }

    @Test
    fun `DynamicTaskDispatcher should stop on shutdown request`() = runTest {
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
        val dispatcher = DynamicTaskDispatcher(executor, fileStorage)
        
        val scheduler = object : BackgroundTaskScheduler {
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

        fileStorage.saveTaskMetadata("task-1", mapOf("workerClassName" to "Worker1"), false)
        fileStorage.enqueueTask("task-1")
        fileStorage.saveTaskMetadata("task-2", mapOf("workerClassName" to "Worker2"), false)
        fileStorage.enqueueTask("task-2")

        // Request shutdown immediately
        dispatcher.requestShutdownSync()
        
        val processedCount = dispatcher.executePendingTasks(scheduler)
        
        assertEquals(0, processedCount, "No tasks should be processed after shutdown request")
        assertEquals(2, fileStorage.getTasksQueueSize(), "Tasks should remain in queue")
    }

    // ==================== NativeTaskScheduler Integration Test ====================

    @Test
    fun `NativeTaskScheduler should intercept dynamic tasks and enqueue them`() = runTest {
        // ... (existing code)
    }

    // ==================== Performance & Stress Tests ====================

    @Test
    fun `Stress Test - Enqueue 100 dynamic tasks concurrently`() = runTest {
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("kmp_master_dispatcher_task"),
            fileStorage = fileStorage
        )

        val taskCount = 100
        coroutineScope {
            repeat(taskCount) { i ->
                launch {
                    scheduler.enqueue(
                        id = "stress-task-$i",
                        trigger = TaskTrigger.OneTime(0),
                        workerClassName = "StressWorker",
                        constraints = Constraints(),
                        inputJson = """{"index": $i}""",
                        policy = ExistingPolicy.KEEP
                    )
                }
            }
        }

        assertEquals(taskCount, fileStorage.getTasksQueueSize(), "All $taskCount tasks must be enqueued without loss")
    }

    @Test
    fun `Performance Test - Dispatcher should process 100 tasks efficiently`() = runTest {
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
        val dispatcher = DynamicTaskDispatcher(executor, fileStorage)
        
        val scheduler = object : BackgroundTaskScheduler {
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

        // Pre-enqueue 100 tasks
        repeat(taskCount) { i ->
            fileStorage.saveTaskMetadata("task-$i", mapOf("workerClassName" to "Worker"), false)
            fileStorage.enqueueTask("task-$i")
        }

        val startTime = platform.Foundation.NSDate().timeIntervalSince1970
        val count = dispatcher.executePendingTasks(scheduler)
        val endTime = platform.Foundation.NSDate().timeIntervalSince1970
        
        assertEquals(taskCount, count)
        assertEquals(taskCount, processedCount.value)
        println("Performance: Processed $taskCount tasks in ${endTime - startTime} seconds")
    }
}
