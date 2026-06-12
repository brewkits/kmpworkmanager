@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P0 bug: [DynamicTaskDispatcher] dequeued each task
 * before executing it but never re-enqueued on retryable failures. Result: any task
 * returning [WorkerResult.Retry] (or [WorkerResult.Failure] with `shouldRetry=true`)
 * — the standard transient-network-error path — was silently dropped from the queue
 * forever. On a flaky-network day the user lost every background job that hit a
 * transient error.
 *
 * The fix routes one-time-task results through [DynamicTaskDispatcher.handleOneTimeResult]:
 *
 *  - `Success`               → drop metadata, do not re-enqueue
 *  - `Failure(shouldRetry=false)` → drop metadata
 *  - `Failure(shouldRetry=true)`  → re-enqueue with incremented attempt counter
 *  - `Retry(attemptCap)`     → re-enqueue, capped at the worker's attemptCap
 *  - Cap reached             → drop metadata, log warning
 *
 * This test pins each branch.
 */
class V250DynamicRetryTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_retry_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

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

    /**
     * Worker factory that returns a sequence of [WorkerResult]s, one per execution.
     * Lets a test simulate "Retry → Retry → Success" across re-enqueue cycles.
     */
    private class ScriptedFactory(private val script: List<WorkerResult>) : IosWorkerFactory {
        var callCount = 0
            private set
        val executedClassNames = mutableListOf<String>()

        override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
            override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                executedClassNames.add(workerClassName)
                val idx = callCount.coerceAtMost(script.size - 1)
                callCount++
                return script[idx]
            }
        }
    }

    private suspend fun enqueueOneTimeTask(storage: IosFileStorage, id: String, workerClass: String) {
        storage.saveTaskMetadata(id, mapOf("workerClassName" to workerClass), periodic = false)
        storage.enqueueTask(id)
    }

    @Test
    fun retry_isReEnqueued_withIncrementedAttemptCounter() = runTest {
        val storage = makeStorage("retry-reenqueue")
        try {
            val factory = ScriptedFactory(listOf(WorkerResult.Retry("network blip")))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-flaky", "FlakyWorker")
            assertEquals(1, storage.getTasksQueueSize())

            dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(
                1, storage.getTasksQueueSize(),
                "REGRESSION: Retry result must re-enqueue the task. Pre-fix dispatcher " +
                    "dropped it from the queue → silent data loss on flaky networks."
            )
            val updatedMeta = storage.loadTaskMetadata("task-flaky", periodic = false)
            assertEquals(
                "2", updatedMeta?.get("kmpAttemptCount"),
                "Re-enqueue must persist the incremented attempt counter so a crash " +
                    "between enqueue and the next execution can't reset the count."
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun failure_withShouldRetryTrue_isReEnqueued() = runTest {
        val storage = makeStorage("failure-shouldretry")
        try {
            val factory = ScriptedFactory(listOf(WorkerResult.Failure("transient", shouldRetry = true)))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-fail-retry", "FailRetryWorker")
            dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(
                1, storage.getTasksQueueSize(),
                "Failure(shouldRetry=true) must follow the same re-enqueue path as Retry"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun success_doesNotReEnqueue_dropsMetadata() = runTest {
        val storage = makeStorage("success-drop")
        try {
            val factory = ScriptedFactory(listOf(WorkerResult.Success()))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-ok", "OkWorker")
            dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(0, storage.getTasksQueueSize(), "Success must not re-enqueue")
            assertNull(
                storage.loadTaskMetadata("task-ok", periodic = false),
                "Success must drop one-time task metadata to prevent storage growth"
            )
        } finally {
            storage.close()
        }
    }

    /**
     * Regression for the iOS-18-flaky failure of [success_doesNotReEnqueue_dropsMetadata].
     *
     * Root cause: [IosFileStorage]'s init block launches a background job on
     * Dispatchers.Default that initialized `tasksQueueSizeCounter` with an unconditional
     * `counter.value = diskSize`. That write races with [IosFileStorage.enqueueTask],
     * which sets the same counter under its mutex. When the background job won the race it
     * reset the counter to 0 *after* enqueue set it to 1 — so getTasksQueueSize() reported
     * an empty queue, the dispatcher skipped the task, and its metadata was never dropped.
     * The assertNull above then failed, but only when the threads happened to interleave
     * that way (observed on the iOS 18.2 CI runner, never locally).
     *
     * Each iteration constructs a fresh storage so a fresh init-job races a fresh enqueue,
     * amplifying the window. On the unfixed code this fails within a handful of iterations;
     * with the CAS-from-UNINITIALIZED fix it is deterministically green.
     */
    @Test
    fun success_dropsMetadata_underConstructEnqueueRace() = runTest {
        repeat(40) { i ->
            val storage = makeStorage("race-$i")
            try {
                val factory = ScriptedFactory(listOf(WorkerResult.Success()))
                val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

                enqueueOneTimeTask(storage, "task-$i", "OkWorker")
                dispatcher.executePendingTasks(makeSchedulerStub())

                assertEquals(0, storage.getTasksQueueSize(), "iteration $i: Success must not re-enqueue")
                assertNull(
                    storage.loadTaskMetadata("task-$i", periodic = false),
                    "iteration $i: Success must drop metadata — counter-init race must not skip the task"
                )
            } finally {
                storage.close()
            }
        }
    }

    @Test
    fun failure_withoutRetry_doesNotReEnqueue_dropsMetadata() = runTest {
        val storage = makeStorage("failure-terminal")
        try {
            val factory = ScriptedFactory(listOf(WorkerResult.Failure("bad input", shouldRetry = false)))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-bad", "BadWorker")
            dispatcher.executePendingTasks(makeSchedulerStub())

            assertEquals(0, storage.getTasksQueueSize(), "Terminal failure must not re-enqueue")
            assertNull(
                storage.loadTaskMetadata("task-bad", periodic = false),
                "Terminal failure must drop metadata"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun retry_respectsAttemptCap_dropsAfterCap() = runTest {
        val storage = makeStorage("attemptcap")
        try {
            // Worker always returns Retry with cap=2 (original + 1 retry, then stop).
            val factory = ScriptedFactory(listOf(WorkerResult.Retry("always", attemptCap = 2)))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-cap", "CapWorker")

            // Run 1: original attempt (count=1 → 2). Re-enqueued.
            dispatcher.executePendingTasks(makeSchedulerStub())
            assertEquals(1, storage.getTasksQueueSize(), "first attempt: re-enqueue (cap=2)")

            // Run 2: now at attempt count=2 → 3 which exceeds cap=2 → drop.
            dispatcher.executePendingTasks(makeSchedulerStub())
            assertEquals(
                0, storage.getTasksQueueSize(),
                "second attempt exceeds attemptCap=2 → must drop task to prevent infinite loop"
            )
            assertNull(
                storage.loadTaskMetadata("task-cap", periodic = false),
                "cap-exceeded task must have its metadata cleaned up"
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun retry_unboundedCap_isLimitedByDefaultCap() = runTest {
        val storage = makeStorage("default-cap")
        try {
            // Worker never specifies attemptCap. Default cap (DEFAULT_ATTEMPT_CAP = 5)
            // must still apply so a poison pill can't loop forever.
            val factory = ScriptedFactory(listOf(WorkerResult.Retry("forever", attemptCap = null)))
            val dispatcher = DynamicTaskDispatcher(SingleTaskExecutor(factory), storage)

            enqueueOneTimeTask(storage, "task-forever", "ForeverWorker")

            // Run dispatch many times. Without a default cap this would loop indefinitely;
            // with the default cap of 5 it must drop after the 5th execution.
            var executed = 0
            repeat(10) {
                if (storage.getTasksQueueSize() == 0) return@repeat
                dispatcher.executePendingTasks(makeSchedulerStub())
                executed++
            }

            assertTrue(
                executed <= 5,
                "DEFAULT_ATTEMPT_CAP must bound total executions. Got $executed runs " +
                    "for a worker that never specified attemptCap."
            )
            assertEquals(0, storage.getTasksQueueSize(), "task must be dropped at the default cap")
        } finally {
            storage.close()
        }
    }
}
