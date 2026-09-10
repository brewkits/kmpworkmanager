@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Each test gets its own `baseDirectory`, per CLAUDE.md's iOS convention.
 *
 * It did not until v3.5.0: both tests used `NativeTaskScheduler()`'s default constructor,
 * which points at the process-wide storage location that every other test in this binary also
 * writes to. The assertions were written defensively around that — relative counts, id-targeted
 * lookups, a drain-and-restore helper — and it still was not enough. After a day of repeated
 * suite runs the shared dynamic queue had accumulated 29 KB of leftovers from
 * `RaceConditionTest`, and both tests failed at their *setup* assertion rather than at anything
 * they were written to check. They failed on unmodified code and passed immediately after
 * deleting the directory, which is the signature of a test that reports on its neighbours
 * instead of on the change in front of it — it came close to causing a correct optimisation to
 * be reverted during the v3.5.0 audit (see I-27).
 *
 * The assertions stay relative anyway: cheap, and they document that nothing here depends on
 * this test's id being first in the queue.
 */
/**
 * A scheduler backed by its own temp directory, so one test cannot see another's queue.
 * Mirrors the setup every other iOS test uses (see `V331PathTraversalTest.setup()`).
 */
private fun makeIsolatedScheduler(tag: String): NativeTaskScheduler {
    val name = "kmp_v341_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
    val dir = NSURL.fileURLWithPath("${NSTemporaryDirectory()}$name")
    NSFileManager.defaultManager.createDirectoryAtURL(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    return NativeTaskScheduler(
        fileStorage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = dir
        )
    )
}

private suspend fun drainQueueUntilFound(fileStorage: IosFileStorage, targetId: String): Boolean {
    val displaced = mutableListOf<String>()
    var found = false
    while (true) {
        val id = fileStorage.dequeueTask() ?: break
        if (id == targetId) {
            found = true
            break
        }
        displaced.add(id)
    }
    // Put back everything that wasn't ours — leave other tests' leftover state as-is.
    displaced.forEach { fileStorage.enqueueTask(it) }
    return found
}

/**
 * Regression net for #101: `ExistingPolicy.KEEP` silently behaved like `REPLACE` for a
 * "dynamic" task id (one not declared in Info.plist — the common case, since ids are
 * typically per-instance and can't realistically be pre-declared statically).
 *
 * **Root cause**: `handleExistingPolicy`'s KEEP branch used `isTaskPending(id)`, which
 * queries `BGTaskScheduler` directly. A dynamic id is never submitted to `BGTaskScheduler`
 * under its own identifier — only the shared Master Dispatcher identifier is ever submitted
 * (see `submitTaskRequest`'s dynamic-task branch) — so `isTaskPending(id)` was always `false`
 * for a dynamic id, in production and in tests. Every repeat `enqueue(id, policy = KEEP)`
 * call therefore treated existing metadata as stale, deleted it, and re-enqueued the id onto
 * the file-backed dynamic queue — which has no id-dedup — duplicating the task if the first
 * copy hadn't been dequeued yet, and discarding whatever the first call's metadata held.
 *
 * **The fix**: for a dynamic id, KEEP's staleness check now uses
 * `fileStorage.isTaskInDynamicQueue(id)` instead — the same "is this still waiting to run"
 * signal `computeIosTaskState`/`observeTaskState` already use for this exact id class.
 */
class V341KeepPolicyDynamicIdTest {

    @Test
    fun `KEEP on a still-queued dynamic id does not duplicate it in the dynamic queue`() = runTest {
        val scheduler = makeIsolatedScheduler("still-queued")
        try {
            val taskId = "keep-dynamic-still-queued-${kotlin.random.Random.nextInt()}"
            val sizeBefore = scheduler.fileStorage.getTasksQueueSize()

            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(0),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = "first",
                policy = ExistingPolicy.KEEP
            )
            assertTrue(
                scheduler.fileStorage.isTaskInDynamicQueue(taskId),
                "test setup: task must be sitting in the dynamic queue after the first enqueue"
            )
            assertEquals(sizeBefore + 1, scheduler.fileStorage.getTasksQueueSize(), "test setup: queue must have grown by exactly 1 entry")

            // Second KEEP-policy enqueue for the SAME id while it is still queued (not yet
            // dequeued for execution). Before the fix, isTaskPending(id) was always false for
            // this dynamic id, so this call always treated the existing metadata as stale and
            // re-enqueued — pushing the queue size up by a second entry.
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(0),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = "second",
                policy = ExistingPolicy.KEEP
            )

            assertEquals(
                sizeBefore + 1,
                scheduler.fileStorage.getTasksQueueSize(),
                "KEEP must not duplicate a still-queued dynamic task in the dynamic queue"
            )

            val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
            assertNotNull(metadata, "metadata must still exist")
            assertEquals(
                "first",
                metadata["inputJson"],
                "KEEP must preserve the original metadata, not overwrite it with the second call's input"
            )
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `KEEP on a dynamic id no longer in the queue is still treated as stale and rescheduled`() = runTest {
        val scheduler = makeIsolatedScheduler("dequeued")
        try {
            val taskId = "keep-dynamic-dequeued-${kotlin.random.Random.nextInt()}"

            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(0),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = "first",
                policy = ExistingPolicy.KEEP
            )
            assertTrue(scheduler.fileStorage.isTaskInDynamicQueue(taskId), "test setup: task must be queued")

            // Simulate the Master Dispatcher having already dequeued it for execution (metadata
            // is still on disk — deleted only once the run actually completes — but the id is
            // no longer sitting in the queue). The queue is FIFO and shared with other tests'
            // leftover entries in this binary, so drain (and restore) until we specifically find
            // our own id rather than assuming it's at the front.
            val found = drainQueueUntilFound(scheduler.fileStorage, taskId)
            assertTrue(found, "test setup: must find and dequeue the task we just enqueued")
            assertTrue(!scheduler.fileStorage.isTaskInDynamicQueue(taskId), "test setup: task must no longer be queued")

            // This is the pre-existing (unchanged by this fix) narrow-window behavior: with no
            // live in-memory registry for standalone dynamic tasks to say "still genuinely
            // executing", a dequeued-but-present metadata is treated as stale and rescheduled —
            // same as before this fix, for this specific sub-case.
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(0),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = "second",
                policy = ExistingPolicy.KEEP
            )

            assertTrue(
                scheduler.fileStorage.isTaskInDynamicQueue(taskId),
                "a dequeued-but-orphaned dynamic task must be rescheduled (re-enqueued) by KEEP, not left stuck forever"
            )
            val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
            assertNotNull(metadata)
            assertEquals("second", metadata["inputJson"], "the stale-cleanup path must have overwritten metadata with the new call's input")
        } finally {
            scheduler.close()
        }
    }
}
