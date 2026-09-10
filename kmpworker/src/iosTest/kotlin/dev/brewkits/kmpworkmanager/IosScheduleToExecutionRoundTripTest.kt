@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The round trip from `scheduler.enqueue(...)` to the worker actually running.
 *
 * ### Why this exists
 *
 * The iOS suite proved the two halves separately and never the join. `NativeTaskScheduler`
 * tests assert what `enqueue` writes to disk; `IosDynamicTaskDispatcherTest` asserts the
 * dispatcher runs what it finds — but it hand-seeds `saveTaskMetadata` + `enqueueTask`
 * directly, so nothing checked that what `enqueue` *actually writes* is what the dispatcher
 * *actually needs*.
 *
 * That gap is not academic. On a physical iPhone this release's demo showed tasks being
 * enqueued correctly and then never running, because `BGTaskScheduler` does not fire while the
 * app is in the foreground — so the only end-to-end evidence available on real hardware stops
 * at "scheduled". Everything after that point was, until this test, unverified as a whole.
 *
 * ### What it does and does not prove
 *
 * It drives the real seam: the public `enqueue()` API writes metadata and queues the id, and
 * the real `DynamicTaskDispatcher` then dequeues it, resolves the worker from that metadata,
 * runs it, and records the result.
 *
 * It does **not** prove that iOS itself invokes the registered BGTask handler — that is
 * Apple's code, cannot be driven from a test, and is the one link that stays outside this
 * suite by construction.
 */
class IosScheduleToExecutionRoundTripTest {

    private lateinit var dir: NSURL
    private lateinit var storage: IosFileStorage

    private class RecordingStore : ExecutionHistoryStore {
        val records = mutableListOf<ExecutionRecord>()
        override suspend fun save(record: ExecutionRecord) { records += record }
        override suspend fun getRecords(limit: Int): List<ExecutionRecord> = records.takeLast(limit)
        override suspend fun clear() { records.clear() }
    }

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        dir = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}kmp_roundtrip_${stamp}_${platform.posix.rand()}"
        )
        NSFileManager.defaultManager.createDirectoryAtURL(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        storage = IosFileStorage(config = IosFileStorageConfig(isTestMode = true), baseDirectory = dir)
    }

    @AfterTest
    fun tearDown() {
        KmpWorkManagerRuntime.reset()
        NSFileManager.defaultManager.removeItemAtURL(dir, error = null)
    }

    private fun schedulerStub(): BackgroundTaskScheduler = object : BackgroundTaskScheduler {
        override suspend fun enqueue(
            id: String, trigger: TaskTrigger, workerClassName: String, constraints: Constraints,
            inputJson: String?, policy: ExistingPolicy, tags: Set<String>, deadlineMs: Long?
        ) = dev.brewkits.kmpworkmanager.background.domain.ScheduleResult.ACCEPTED
        override fun cancel(id: String) = Unit
        override fun cancelAll() = Unit
        override fun beginWith(task: TaskRequest): TaskChain = error("not needed")
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = error("not needed")
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) = Unit
        override fun flushPendingProgress() = Unit
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() = Unit
    }

    /**
     * The join: what `enqueue()` persists is exactly what the dispatcher needs to run the task.
     */
    @Test
    fun `a task enqueued through the public API is later executed by the dispatcher`() = runTest {
        val history = RecordingStore()
        KmpWorkManagerRuntime.setHistoryStore(history)

        val ran = mutableListOf<Pair<String, String?>>()
        val factory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                    ran += workerClassName to input
                    return WorkerResult.Success(message = "done")
                }
            }
        }

        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

        // 1. Schedule through the public API — nothing hand-seeded.
        scheduler.enqueue(
            id = "roundtrip-task",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "com.example.SyncWorker",
            constraints = Constraints(),
            inputJson = """{"album":"holiday"}""",
            policy = ExistingPolicy.REPLACE
        )
        assertTrue(
            storage.isTaskInDynamicQueue("roundtrip-task"),
            "precondition: enqueue must have queued the task"
        )

        // 2. Run the dispatcher exactly as the BGTask handler would.
        val processed = dispatcher.executePendingTasks(schedulerStub())

        assertEquals(1, processed, "the dispatcher must process the queued task")
        assertEquals<List<Pair<String, String?>>>(
            listOf("com.example.SyncWorker" to """{"album":"holiday"}"""),
            ran,
            "the worker must be resolved from the metadata enqueue() wrote, and receive its input"
        )
        assertEquals(0, storage.getTasksQueueSize(), "the task must leave the queue once run")

        // 3. And the run must be visible afterwards.
        val record = history.records.singleOrNull()
        assertNotNull(record, "an executed task must leave an ExecutionRecord")
        assertEquals(ExecutionStatus.SUCCESS, record.status)
        assertEquals("roundtrip-task", record.chainId)
        assertEquals(listOf("com.example.SyncWorker"), record.workerClassNames)
        assertEquals("ios", record.platform)
    }

    /**
     * A failing worker still completes the round trip: the task leaves the queue and the
     * failure is recorded, rather than the run vanishing.
     */
    @Test
    fun `a failing task is recorded rather than silently disappearing`() = runTest {
        val history = RecordingStore()
        KmpWorkManagerRuntime.setHistoryStore(history)

        val factory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                    WorkerResult.Failure("upload rejected", shouldRetry = false)
            }
        }

        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

        scheduler.enqueue(
            id = "roundtrip-failure",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "com.example.UploadWorker",
            policy = ExistingPolicy.REPLACE
        )
        dispatcher.executePendingTasks(schedulerStub())

        val record = history.records.singleOrNull()
        assertNotNull(record, "a failed task must still leave a record")
        assertEquals(ExecutionStatus.FAILURE, record.status)
        assertEquals("upload rejected", record.errorMessage, "the reason must survive to history")
    }
}
