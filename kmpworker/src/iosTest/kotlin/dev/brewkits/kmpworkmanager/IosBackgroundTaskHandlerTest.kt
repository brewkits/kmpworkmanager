@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosBackgroundTaskHandler
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for [IosBackgroundTaskHandler].
 *
 * BGTask itself cannot be instantiated in tests (it is an abstract OS class),
 * so these tests cover the pure-Kotlin helpers:
 *   - [IosBackgroundTaskHandler.resolveTaskMetadata]
 *   - [IosBackgroundTaskHandler.reschedulePeriodicTask]
 *
 * The full round-trip (handler → BGTask.setTaskCompleted) is exercised in the
 * demo app's manual iOS testing checklist.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosBackgroundTaskHandlerTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun makeTempDir(name: String): NSURL {
        val tmp = NSURL.fileURLWithPath(NSTemporaryDirectory())
        val dir = tmp.URLByAppendingPathComponent("kmphandler_$name", isDirectory = true)!!
        NSFileManager.defaultManager.removeItemAtURL(dir, null)
        NSFileManager.defaultManager.createDirectoryAtURL(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        return dir
    }

    private fun makeStorage(name: String): IosFileStorage {
        return IosFileStorage(
            config = IosFileStorageConfig(diskSpaceBufferBytes = 0L),
            baseDirectory = makeTempDir(name)
        )
    }

    /**
     * Fake [BackgroundTaskScheduler] that records the last [enqueue] call for assertion.
     */
    private class FakeScheduler : BackgroundTaskScheduler {
        data class EnqueueCall(
            val id: String,
            val trigger: TaskTrigger,
            val workerClassName: String,
            val constraints: Constraints,
            val inputJson: String?,
            val policy: ExistingPolicy
        )

        var lastEnqueue: EnqueueCall? = null

        override suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy
        ): ScheduleResult {
            lastEnqueue = EnqueueCall(id, trigger, workerClassName, constraints, inputJson, policy)
            return ScheduleResult.ACCEPTED
        }

        override fun cancel(id: String) = Unit
        override fun cancelAll() = Unit
        override fun beginWith(task: TaskRequest): TaskChain = throw UnsupportedOperationException()
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = throw UnsupportedOperationException()
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) = Unit
        override fun flushPendingProgress() = Unit
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() = Unit
    }

    // ──────────────────────────────────────────────────────────────────────────
    // resolveTaskMetadata — one-time tasks
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `resolveTaskMetadata returns null when no metadata exists`() {
        val storage = makeStorage("no-meta")
        val result = IosBackgroundTaskHandler.resolveTaskMetadata("nonexistent-task", storage)
        assertNull(result, "Expected null for task with no stored metadata")
    }

    @Test
    fun `resolveTaskMetadata resolves one-time task with rawMeta`() {
        val storage = makeStorage("one-time-meta")
        val taskId = "window-task"
        storage.saveTaskMetadata(
            id = taskId,
            metadata = mapOf(
                "workerClassName" to "WindowWorker",
                "inputJson" to "",
                "windowLatest" to "1700000000000"
            ),
            periodic = false
        )

        val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, storage)

        assertNotNull(meta)
        assertEquals("WindowWorker", meta.workerClassName)
        assertEquals(false, meta.isPeriodic)
        assertNotNull(meta.rawMeta)
        assertEquals("1700000000000", meta.rawMeta!!["windowLatest"])
    }

    @Test
    fun `resolveTaskMetadata returns null when one-time workerClassName is empty`() {

        val storage = makeStorage("empty-worker")
        storage.saveTaskMetadata(
            id = "task-empty-worker",
            metadata = mapOf("workerClassName" to "", "inputJson" to ""),
            periodic = false
        )
        val result = IosBackgroundTaskHandler.resolveTaskMetadata("task-empty-worker", storage)
        assertNull(result, "Empty workerClassName should return null")
    }

    @Test
    fun `resolveTaskMetadata handles null inputJson for one-time task`() {
        val storage = makeStorage("no-input")
        val taskId = "task-no-input"
        storage.saveTaskMetadata(
            id = taskId,
            metadata = mapOf("workerClassName" to "LightWorker", "inputJson" to ""),
            periodic = false
        )

        val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, storage)
        assertNotNull(meta)
        assertEquals("LightWorker", meta.workerClassName)
        assertNull(meta.inputJson, "Empty inputJson should be resolved as null")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // resolveTaskMetadata — periodic tasks
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `resolveTaskMetadata resolves periodic task`() {
        val storage = makeStorage("periodic")
        val taskId = "sync-periodic"
        storage.saveTaskMetadata(
            id = taskId,
            metadata = mapOf(
                "isPeriodic" to "true",
                "intervalMs" to "3600000",
                "workerClassName" to "SyncWorker",
                "inputJson" to "",
                "requiresNetwork" to "true",
                "requiresCharging" to "false",
                "isHeavyTask" to "false",
                "anchoredStartMs" to "1700000000000"
            ),
            periodic = true
        )

        val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, storage)

        assertNotNull(meta)
        assertEquals("SyncWorker", meta.workerClassName)
        assertNull(meta.inputJson)
        assertEquals(true, meta.isPeriodic)
        assertNotNull(meta.rawMeta, "Periodic tasks must carry rawMeta for reschedule parameters")
        assertEquals("3600000", meta.rawMeta!!["intervalMs"])
        assertEquals("true", meta.rawMeta["requiresNetwork"])
    }

    @Test
    fun `resolveTaskMetadata prefers periodic storage over one-time when both exist`() {
        val storage = makeStorage("prefer-periodic")
        val taskId = "dual-task"
        // Store in both — periodic should win
        storage.saveTaskMetadata(
            id = taskId,
            metadata = mapOf(
                "isPeriodic" to "true",
                "intervalMs" to "900000",
                "workerClassName" to "PeriodicWorker",
                "inputJson" to "",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false"
            ),
            periodic = true
        )
        storage.saveTaskMetadata(
            id = taskId,
            metadata = mapOf("workerClassName" to "OneTimeWorker", "inputJson" to ""),
            periodic = false
        )

        val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, storage)

        assertNotNull(meta)
        assertEquals("PeriodicWorker", meta.workerClassName, "Periodic storage must take precedence")
        assertEquals(true, meta.isPeriodic)
    }

    @Test
    fun `resolveTaskMetadata returns null when periodic workerClassName is empty`() {
        val storage = makeStorage("periodic-empty-worker")
        storage.saveTaskMetadata(
            id = "task-bad",
            metadata = mapOf(
                "isPeriodic" to "true",
                "intervalMs" to "3600000",
                "workerClassName" to "",
                "inputJson" to ""
            ),
            periodic = true
        )
        val result = IosBackgroundTaskHandler.resolveTaskMetadata("task-bad", storage)
        assertNull(result, "Periodic task with empty workerClassName should return null")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // reschedulePeriodicTask
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `reschedulePeriodicTask enqueues with correct parameters`() = runTest {
        val fakeScheduler = FakeScheduler()
        val meta = mapOf(
            "intervalMs" to "7200000",
            "requiresNetwork" to "true",
            "requiresCharging" to "true",
            "isHeavyTask" to "false"
        )

        IosBackgroundTaskHandler.reschedulePeriodicTask(
            taskId = "upload-task",
            workerClassName = "UploadWorker",
            inputJson = "{\"batch\":10}",
            rawMeta = meta,
            scheduler = fakeScheduler
        )

        val call = fakeScheduler.lastEnqueue
        assertNotNull(call, "reschedulePeriodicTask should call scheduler.enqueue")
        assertEquals("upload-task", call.id)
        assertEquals("UploadWorker", call.workerClassName)
        assertEquals("{\"batch\":10}", call.inputJson)
        assertEquals(ExistingPolicy.REPLACE, call.policy)

        val trigger = call.trigger as? TaskTrigger.Periodic
        assertNotNull(trigger)
        assertEquals(7200000L, trigger.intervalMs)

        assertEquals(true, call.constraints.requiresNetwork)
        assertEquals(true, call.constraints.requiresCharging)
        assertEquals(false, call.constraints.isHeavyTask)
    }

    @Test
    fun `reschedulePeriodicTask skips enqueue when intervalMs is missing`() = runTest {
        val fakeScheduler = FakeScheduler()

        IosBackgroundTaskHandler.reschedulePeriodicTask(
            taskId = "broken-periodic",
            workerClassName = "SomeWorker",
            inputJson = null,
            rawMeta = mapOf("requiresNetwork" to "false"),  // intentionally missing intervalMs
            scheduler = fakeScheduler
        )

        assertNull(fakeScheduler.lastEnqueue, "Must not enqueue when intervalMs is absent")
    }

    @Test
    fun `reschedulePeriodicTask skips enqueue when rawMeta is null`() = runTest {
        val fakeScheduler = FakeScheduler()

        IosBackgroundTaskHandler.reschedulePeriodicTask(
            taskId = "task",
            workerClassName = "Worker",
            inputJson = null,
            rawMeta = null,
            scheduler = fakeScheduler
        )

        assertNull(fakeScheduler.lastEnqueue, "Must not enqueue when rawMeta is null")
    }

    @Test
    fun `reschedulePeriodicTask handles null inputJson correctly`() = runTest {
        val fakeScheduler = FakeScheduler()
        val meta = mapOf(
            "intervalMs" to "1800000",
            "requiresNetwork" to "false",
            "requiresCharging" to "false",
            "isHeavyTask" to "true"
        )

        IosBackgroundTaskHandler.reschedulePeriodicTask(
            taskId = "heavy-task",
            workerClassName = "HeavyWorker",
            inputJson = null,
            rawMeta = meta,
            scheduler = fakeScheduler
        )

        val call = fakeScheduler.lastEnqueue
        assertNotNull(call)
        assertNull(call.inputJson)
        assertEquals(true, call.constraints.isHeavyTask)
    }
}
