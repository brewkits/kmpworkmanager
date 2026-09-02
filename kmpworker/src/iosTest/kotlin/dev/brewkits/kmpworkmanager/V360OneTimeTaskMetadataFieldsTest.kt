@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for a bug found while wiring [DynamicQueueConstraintSummary.allRequireCharging]:
 * [NativeTaskScheduler.scheduleOneTimeTask]'s persisted metadata never wrote
 * `requiresNetwork`/`requiresCharging`/`isHeavyTask` — only `schedulePeriodicTask` did. Every
 * one-time task (dedicated-identifier or dynamic-queue) has always had these three fields
 * silently absent from its metadata file, long before this session.
 *
 * The most severe consequence: [IosBackgroundTaskHandler.handleOneTimeTaskResult] rebuilds
 * `Constraints` for a retry via `reconstructConstraintsFromMetadata(rawMeta)`, reading exactly
 * these three keys. With them absent, EVERY dedicated-identifier one-time task's first retry
 * silently resets `isHeavyTask` to `false` — downgrading its re-submission from
 * `BGProcessingTaskRequest` (minutes of budget) to `BGAppRefreshTaskRequest` (~30s hard ceiling)
 * for every subsequent attempt, regardless of what the caller originally requested.
 */
class V360OneTimeTaskMetadataFieldsTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_onetime_meta_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    @Test
    fun `scheduleOneTimeTask persists requiresNetwork and requiresCharging and isHeavyTask`() = runTest {
        val storage = makeStorage("persist-fields")
        val scheduler = NativeTaskScheduler(fileStorage = storage)

        scheduler.enqueue(
            id = "field-check-task",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "TestWorker",
            constraints = Constraints(
                requiresNetwork = true,
                requiresCharging = true,
                isHeavyTask = true
            )
        )

        val meta = storage.loadTaskMetadata("field-check-task", periodic = false)
        assertNotNull(meta)
        assertEquals("true", meta["requiresNetwork"], "requiresNetwork must be persisted for one-time tasks, matching periodic")
        assertEquals("true", meta["requiresCharging"], "requiresCharging must be persisted for one-time tasks, matching periodic")
        assertEquals("true", meta["isHeavyTask"], "isHeavyTask must be persisted for one-time tasks, matching periodic")

        storage.close()
    }

    @Test
    fun `scheduleOneTimeTask persists false values too not just true`() = runTest {
        val storage = makeStorage("persist-false")
        val scheduler = NativeTaskScheduler(fileStorage = storage)

        scheduler.enqueue(
            id = "field-check-task-2",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "TestWorker",
            constraints = Constraints() // every field default/false
        )

        val meta = storage.loadTaskMetadata("field-check-task-2", periodic = false)
        assertNotNull(meta)
        assertEquals("false", meta["requiresNetwork"])
        assertEquals("false", meta["requiresCharging"])
        assertEquals("false", meta["isHeavyTask"])

        storage.close()
    }

    /**
     * The severe case: a dedicated-identifier heavy task's `isHeavyTask` must survive its
     * FIRST retry — i.e. `reconstructConstraintsFromMetadata` must read back `true`, not
     * silently default to `false` because the key was never written in the first place.
     */
    @Test
    fun `a heavy dedicated-identifier task stays heavy after a retry re-submission`() = runTest {
        val storage = makeStorage("heavy-survives-retry")
        val scheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("heavy-task"),
            fileStorage = storage
        )

        scheduler.enqueue(
            id = "heavy-task",
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "TestWorker",
            constraints = Constraints(isHeavyTask = true, requiresNetwork = true)
        )

        val meta = IosBackgroundTaskHandler.resolveTaskMetadata("heavy-task", storage)!!
        IosBackgroundTaskHandler.handleOneTimeTaskResult(
            taskId = "heavy-task",
            meta = meta,
            result = WorkerResult.Retry("transient"),
            scheduler = scheduler
        )

        val resolved = IosBackgroundTaskHandler.resolveTaskMetadata("heavy-task", storage)
        assertNotNull(resolved, "Task must survive a retry within its cap")
        assertEquals(
            "true", resolved.rawMeta?.get("isHeavyTask"),
            "REGRESSION: isHeavyTask must survive re-submission — losing it silently downgrades " +
                "the task from BGProcessingTaskRequest (minutes) to BGAppRefreshTaskRequest (~30s)"
        )
        assertEquals(
            "true", resolved.rawMeta?.get("requiresNetwork"),
            "requiresNetwork must also survive re-submission"
        )

        storage.close()
    }
}
