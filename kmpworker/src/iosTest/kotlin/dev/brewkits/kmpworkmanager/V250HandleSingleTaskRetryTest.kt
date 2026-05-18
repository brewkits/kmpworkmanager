@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P0 bug surfaced by the proactive audit:
 * [IosBackgroundTaskHandler.handleSingleTask] silently dropped
 * `WorkerResult.Retry` and `WorkerResult.Failure(shouldRetry = true)` for tasks
 * scheduled with a dedicated Info.plist BGTask identifier. After the worker
 * returned, the handler called `setTaskCompletedWithSuccess(false)` and **never
 * re-submitted a BGTaskRequest** — iOS had no pending request for that identifier,
 * so the work was lost on flaky-network failures.
 *
 * This is the same bug class as BUG 8 ([DynamicTaskDispatcher.handleOneTimeResult],
 * pinned by [V250DynamicRetryTest]) but in the OTHER iOS task entry point — the
 * one used by **dedicated-identifier** BGTasks. Dedicated-identifier scheduling
 * is the more common user path on iOS (a user who calls
 * `BackgroundTaskScheduler.enqueue("my-sync-task", ...)` with an Info.plist
 * identifier hits this path, not the dynamic master dispatcher).
 *
 * **Fix**: [IosBackgroundTaskHandler.handleOneTimeTaskResult] applies the same
 * contract as the dynamic-task fix:
 *
 *  - `Success` → drop metadata, do not re-submit
 *  - `Failure(shouldRetry=false)` → drop metadata (terminal)
 *  - `Failure(shouldRetry=true)` → re-submit via `scheduler.enqueue(...)` with
 *    incremented `kmpAttemptCount`
 *  - `Retry(attemptCap=N)` → re-submit, abandon after N attempts
 *  - `Retry(attemptCap=null)` → re-submit, abandon after DEFAULT_ATTEMPT_CAP (5)
 *
 * The handler exposes `handleOneTimeTaskResult` as `internal` so this test can
 * exercise the branch directly. The full `handleSingleTask` entry point cannot
 * be tested in isolation because it requires an iOS-framework-private `BGTask`
 * instance.
 */
class V250HandleSingleTaskRetryTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_handle_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    /**
     * Recording scheduler: captures every `enqueue(...)` call so the test can
     * assert that retry submitted a new BGTaskRequest (or didn't).
     */
    private class RecordingScheduler(val storage: IosFileStorage) {
        data class EnqueueCall(
            val id: String,
            val trigger: TaskTrigger,
            val workerClassName: String,
            val policy: ExistingPolicy
        )
        val calls = mutableListOf<EnqueueCall>()

        suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy
        ): ScheduleResult {
            calls.add(EnqueueCall(id, trigger, workerClassName, policy))
            return ScheduleResult.ACCEPTED
        }
    }

    /**
     * The real handler uses a [NativeTaskScheduler] for both `enqueue` and
     * `fileStorage`. We exercise the helper via a thin reflection-free shim:
     * a NativeTaskScheduler with the test storage. We then assert against:
     *   1. metadata (storage) — directly testable
     *   2. NativeTaskScheduler.enqueue side effects — observable via the queue
     *      file storage (re-submit produces a saved one-time task metadata file
     *      with incremented `kmpAttemptCount`).
     */
    private fun makeScheduler(storage: IosFileStorage): NativeTaskScheduler =
        NativeTaskScheduler(
            additionalPermittedTaskIds = setOf("test-task"),
            fileStorage = storage
        )

    private suspend fun seedOneTimeTask(storage: IosFileStorage, taskId: String) {
        storage.saveTaskMetadata(
            taskId,
            mapOf(
                "workerClassName" to "TestWorker",
                "requiresNetwork" to "false",
                "requiresCharging" to "false",
                "isHeavyTask" to "false"
            ),
            periodic = false
        )
    }

    private fun makeMeta(taskId: String, storage: IosFileStorage): IosBackgroundTaskHandler.TaskMeta =
        IosBackgroundTaskHandler.resolveTaskMetadata(taskId, storage)!!

    @Test
    fun retry_resubmitsWithIncrementedAttemptCounter() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("retry-resubmit")
        )
        val scheduler = makeScheduler(storage)
        try {
            seedOneTimeTask(storage, "test-task")
            val meta = makeMeta("test-task", storage)

            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Retry("network blip"),
                scheduler = scheduler
            )

            val updated = storage.loadTaskMetadata("test-task", periodic = false)
            assertEquals(
                "2", updated?.get("kmpAttemptCount"),
                "REGRESSION: Retry must persist incremented attempt counter. Pre-fix, " +
                    "handleSingleTask silently dropped Retry — iOS had no pending " +
                    "BGTaskRequest for the dedicated identifier and the work was lost."
            )
            assertEquals(
                "TestWorker", updated?.get("workerClassName"),
                "Existing metadata fields must be preserved across re-submit."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun failure_withShouldRetryTrue_resubmits() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("failure-retry")
        )
        val scheduler = makeScheduler(storage)
        try {
            seedOneTimeTask(storage, "test-task")
            val meta = makeMeta("test-task", storage)

            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Failure("transient", shouldRetry = true),
                scheduler = scheduler
            )

            val updated = storage.loadTaskMetadata("test-task", periodic = false)
            assertEquals(
                "2", updated?.get("kmpAttemptCount"),
                "Failure(shouldRetry=true) must follow the same re-submit path as Retry"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun success_dropsMetadata() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("success")
        )
        val scheduler = makeScheduler(storage)
        try {
            seedOneTimeTask(storage, "test-task")
            val meta = makeMeta("test-task", storage)

            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Success(),
                scheduler = scheduler
            )

            assertNull(
                storage.loadTaskMetadata("test-task", periodic = false),
                "Success must drop the one-time task metadata to prevent storage growth"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun failure_terminal_dropsMetadata() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("failure-terminal")
        )
        val scheduler = makeScheduler(storage)
        try {
            seedOneTimeTask(storage, "test-task")
            val meta = makeMeta("test-task", storage)

            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Failure("bad input", shouldRetry = false),
                scheduler = scheduler
            )

            assertNull(
                storage.loadTaskMetadata("test-task", periodic = false),
                "Terminal failure must drop metadata"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun retry_respectsAttemptCap_dropsAfterCap() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("cap")
        )
        val scheduler = makeScheduler(storage)
        try {
            // Pre-seed metadata at the boundary: attempt counter already at 2, cap = 2.
            // Next retry would be attempt 3 > cap 2 → abandonment.
            storage.saveTaskMetadata(
                "test-task",
                mapOf(
                    "workerClassName" to "TestWorker",
                    "requiresNetwork" to "false",
                    "requiresCharging" to "false",
                    "isHeavyTask" to "false",
                    "kmpAttemptCount" to "2"
                ),
                periodic = false
            )
            val meta = makeMeta("test-task", storage)

            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Retry("always", attemptCap = 2),
                scheduler = scheduler
            )

            assertNull(
                storage.loadTaskMetadata("test-task", periodic = false),
                "Once attempt count would exceed cap, metadata must be deleted to prevent " +
                    "an unbounded retry loop."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun retry_unboundedCap_isLimitedByDefaultCap() = runTest {
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir("default-cap")
        )
        val scheduler = makeScheduler(storage)
        try {
            // Pre-seed at the default-cap boundary. DEFAULT_ATTEMPT_CAP = 5, so attempt
            // counter already at 5 → next retry would be 6 > 5 → abandonment.
            storage.saveTaskMetadata(
                "test-task",
                mapOf(
                    "workerClassName" to "TestWorker",
                    "requiresNetwork" to "false",
                    "requiresCharging" to "false",
                    "isHeavyTask" to "false",
                    "kmpAttemptCount" to "5"
                ),
                periodic = false
            )
            val meta = makeMeta("test-task", storage)

            // Worker doesn't specify attemptCap → default of 5 must apply.
            IosBackgroundTaskHandler.handleOneTimeTaskResult(
                taskId = "test-task",
                meta = meta,
                result = WorkerResult.Retry("forever", attemptCap = null),
                scheduler = scheduler
            )

            assertNull(
                storage.loadTaskMetadata("test-task", periodic = false),
                "DEFAULT_ATTEMPT_CAP must bound a Retry that doesn't specify attemptCap."
            )
        } finally {
            storage.close()
        }
    }
}
