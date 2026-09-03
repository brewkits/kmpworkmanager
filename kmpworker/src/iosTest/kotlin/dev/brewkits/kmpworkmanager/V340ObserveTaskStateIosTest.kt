@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Integration coverage for `NativeTaskScheduler.observeTaskState` itself — the actual
 * `Flow`-returning override, as opposed to `V340ComputeIosTaskStateTest`'s pure-function
 * coverage of the snapshot logic it wraps.
 *
 * Naming convention: `VXYZ...Test` per CLAUDE.md — 3.4.0's.
 */
class V340ObserveTaskStateIosTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_observe_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    @Test
    fun `an id that was never enqueued reports Unknown`() = runTest {
        val storage = makeStorage("never-enqueued")
        val scheduler = NativeTaskScheduler(fileStorage = storage)

        assertEquals(TaskState.Unknown, scheduler.observeTaskState("never-scheduled").first())
        storage.close()
    }

    @Test
    fun `a freshly enqueued dynamic task reports Enqueued`() = runTest {
        val storage = makeStorage("dynamic-enqueued")
        val scheduler = NativeTaskScheduler(fileStorage = storage)

        scheduler.enqueue(
            id = "observe-dynamic-task",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "TestWorker"
        )

        assertEquals(TaskState.Enqueued, scheduler.observeTaskState("observe-dynamic-task").first())
        storage.close()
    }

    @Test
    fun `a cancelled task's metadata is gone so it reports Unknown not Cancelled`() = runTest {
        // Documents a real, honest platform limitation rather than a bug: cancel() deletes
        // metadata outright (NativeTaskScheduler.cancel) without writing any execution-history
        // record, so there is no persisted signal distinguishing "cancelled" from "never
        // existed" once that happens. See observeTaskState's KDoc on BackgroundTaskScheduler.
        val storage = makeStorage("cancelled")
        val scheduler = NativeTaskScheduler(fileStorage = storage)

        scheduler.enqueue(
            id = "observe-cancelled-task",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "TestWorker"
        )
        scheduler.cancel("observe-cancelled-task")

        assertEquals(TaskState.Unknown, scheduler.observeTaskState("observe-cancelled-task").first())
        storage.close()
    }
}
