@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class
)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.coroutines.*
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.ObjCObjectVar

/**
 * Internal dispatcher that processes the queue of dynamic tasks on iOS.
 *
 * Since iOS requires static identifiers in Info.plist, this dispatcher handles
 * all tasks that don't have a dedicated identifier. It runs them as a batch
 * under a single static identifier (kmp_master_dispatcher_task).
 */
public class DynamicTaskDispatcher(
    private val singleTaskExecutor: SingleTaskExecutor,
    private val fileStorage: IosFileStorage = IosFileStorage()
) {
    private val isShuttingDown = AtomicInt(0)

    // Job of the coroutine currently executing [executePendingTasks]. Captured on
    // entry and cleared on exit so that [requestShutdownSync] — called from a
    // non-suspend iOS expiration handler — can actually cancel the in-flight batch.
    //
    // History: previously this class held an unused SupervisorJob + CoroutineScope.
    // `requestShutdownSync` called `job.cancel()` on that scope, but
    // [executePendingTasks] ran on the *caller's* coroutine, so the cancel never
    // reached the running task. The `isShuttingDown` flag stopped the next loop
    // iteration but the current `singleTaskExecutor.executeTask(...)` kept running
    // past the budget — risking SIGKILL via Watchdog.
    private val activeJob = AtomicReference<Job?>(null)

    internal companion object {
        // BGProcessingTask gets "several minutes" from iOS — 3 minutes is a conservative
        // proactive soft limit, leaving the OS ample time to call expirationHandler cleanly.
        const val DEFAULT_BUDGET_MS = 3 * 60 * 1000L

        // Metadata key used to count retry attempts across BGTask invocations.
        // Each Retry/transient-Failure re-enqueue increments this in saved metadata so
        // that a poison-pill task can't loop forever burning quota.
        internal const val META_ATTEMPT_COUNT = "kmpAttemptCount"

        // Metadata key carrying the caller's Constraints.maxRetries (only stamped when set,
        // i.e. >= 0). Bounds a `Failure(shouldRetry = true)` retry loop that carries no
        // per-result attemptCap. Absent → fall back to [DEFAULT_ATTEMPT_CAP].
        internal const val META_MAX_RETRIES = "maxRetries"

        // Hard ceiling when the worker did not specify [WorkerResult.Retry.attemptCap] and
        // the caller set no Constraints.maxRetries. Mirrors WorkManager's default backoff
        // retry budget so behaviour matches the Android side. Same magnitude as
        // ChainProgress.DEFAULT_MAX_RETRIES.
        internal const val DEFAULT_ATTEMPT_CAP = 5
    }

    /**
     * Signal to stop processing the queue. Called from the iOS expiration handler
     * (a non-suspend BGTaskScheduler callback) so this must remain non-suspend.
     *
     * Sets the shutdown flag *and* cancels whichever parent coroutine is currently
     * inside [executePendingTasks]. The cancel propagates into
     * `singleTaskExecutor.executeTask`'s `withTimeout` and stops the in-flight worker
     * cooperatively. Safe to call when no batch is running.
     */
    fun requestShutdownSync() {
        isShuttingDown.value = 1
        activeJob.value?.cancel()
    }

    /**
     * Resets the shutdown state before starting a new batch.
     */
    fun resetShutdownState() {
        isShuttingDown.value = 0
    }

    /**
     * Processes pending tasks from the internal queue.
     *
     * @param scheduler Required to reschedule periodic tasks after execution.
     * @param budgetMs Soft time budget in milliseconds. Stops before starting a new task if the
     *   remaining budget cannot cover [SingleTaskExecutor.DEFAULT_TIMEOUT_MS] plus a 5-second
     *   safety margin. The hard stop remains iOS calling [requestShutdownSync] on expiration.
     * @return Number of tasks processed in this batch.
     */
    suspend fun executePendingTasks(
        scheduler: BackgroundTaskScheduler,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): Int {
        // Register our parent Job so requestShutdownSync (iOS expirationHandler)
        // can actually cancel the in-flight work. Must be cleared in finally.
        val parentJob = currentCoroutineContext()[Job]
        activeJob.value = parentJob

        var processedCount = 0
        val batchStartMs = currentTimeMs()

        // Snapshot the current queue depth before the loop.
        // Without this bound, periodic tasks re-enqueue themselves (via reschedulePeriodicTask)
        // and the while loop picks them up immediately — creating an infinite execution loop
        // within the same BGTask invocation until SIGKILL.
        val tasksToProcess = fileStorage.getTasksQueueSize()
        var remaining = tasksToProcess

        try {
        while (isShuttingDown.value == 0 && remaining > 0) {
            // Proactive budget guard: abort before starting a task we cannot finish in time.
            // Reserves DEFAULT_TIMEOUT_MS + 5s so a max-duration worker doesn't overrun
            // the budget and risk iOS calling the expiration handler mid-execution.
            val budgetLeft = budgetMs - (currentTimeMs() - batchStartMs)
            if (budgetLeft < SingleTaskExecutor.DEFAULT_TIMEOUT_MS + 5_000L) {
                Logger.w(LogTags.SCHEDULER,
                    "DynamicTaskDispatcher: budget almost exhausted (${budgetLeft}ms left), " +
                    "deferring $remaining remaining task(s) to next invocation")
                break
            }

            val taskId = fileStorage.dequeueTask() ?: break
            remaining--

            val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, fileStorage)
            if (meta == null) {
                Logger.e(LogTags.SCHEDULER, "No metadata found for dynamic task '$taskId' - skipping")
                continue
            }

            Logger.i(LogTags.SCHEDULER, "DynamicTaskDispatcher: Executing '$taskId'")

            try {
                val result = singleTaskExecutor.executeTask(meta.workerClassName, meta.inputJson)

                if (meta.isPeriodic) {
                    // Periodic tasks have their own re-schedule contract — every invocation
                    // re-arms the next period regardless of result. The retry path below
                    // applies only to one-time tasks.
                    IosBackgroundTaskHandler.reschedulePeriodicTask(
                        taskId = taskId,
                        workerClassName = meta.workerClassName,
                        inputJson = meta.inputJson,
                        rawMeta = meta.rawMeta,
                        scheduler = scheduler
                    )
                } else {
                    handleOneTimeResult(taskId, meta, result)
                }

                Logger.i(LogTags.SCHEDULER, "Dynamic task '$taskId' finished (result=${result::class.simpleName})")
                processedCount++
            } catch (e: CancellationException) {
                // Parent coroutine cancelled (iOS expirationHandler → requestShutdownSync).
                // MUST rethrow — never swallow CancellationException, or the loop keeps
                // dequeuing tasks after the OS told us to stop, leading to SIGKILL.
                Logger.w(LogTags.SCHEDULER, "Dynamic task '$taskId' cancelled by shutdown request")
                throw e
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Dynamic task '$taskId' threw exception", e)
            }
        }

        // If there are still tasks in the queue and we didn't shut down,
        // reschedule the master dispatcher to continue later.
        if (isShuttingDown.value == 0) {
            val remainingInQueue = fileStorage.getTasksQueueSize()
            if (remainingInQueue > 0) {
                Logger.i(LogTags.SCHEDULER, "$remainingInQueue dynamic task(s) remaining - rescheduling master dispatcher")
                rescheduleMasterDispatcher()
            }
        }

        return processedCount
        } finally {
            // Always clear so a stale Job reference can't leak across batches and
            // can't be cancelled by a late-firing expirationHandler for a prior batch.
            // Compare-and-set guards against the unlikely case where a new batch has
            // already started and registered its own job.
            activeJob.compareAndSet(parentJob, null)
        }
    }

    /**
     * Handles the [WorkerResult] for a one-time task. Pre-fix, this dispatcher
     * dequeued the task before execution and never re-enqueued — so any
     * `WorkerResult.Retry` or `Failure(shouldRetry = true)` was silently dropped,
     * losing the work on flaky networks. The fix mirrors WorkManager's contract:
     *
     *  - `Success`               → drop task metadata, do nothing
     *  - `Failure(shouldRetry=false)` → drop task metadata (terminal failure)
     *  - `Failure(shouldRetry=true)`  → re-enqueue with incremented attempt counter,
     *                                   capped at [DEFAULT_ATTEMPT_CAP]
     *  - `Retry(attemptCap)`     → re-enqueue with incremented attempt counter,
     *                              capped at the worker's `attemptCap` (or default)
     *
     * When the cap is reached the task metadata is deleted and a master-dispatcher
     * re-schedule is *not* needed (the loop's tail handles that).
     */
    private suspend fun handleOneTimeResult(
        taskId: String,
        meta: IosBackgroundTaskHandler.TaskMeta,
        result: WorkerResult
    ) {
        val (shouldRetry, attemptCap, retryReason) = when (result) {
            is WorkerResult.Success -> Triple(false, null, null)
            is WorkerResult.Failure -> Triple(result.shouldRetry, null, result.message)
            is WorkerResult.Retry   -> Triple(true, result.attemptCap, result.reason)
        }

        if (!shouldRetry) {
            // Terminal — drop metadata so storage doesn't accumulate completed/failed tasks.
            fileStorage.deleteTaskMetadata(taskId, periodic = false)
            return
        }

        val rawMeta = meta.rawMeta ?: emptyMap()
        val currentAttempt = rawMeta[META_ATTEMPT_COUNT]?.toIntOrNull() ?: 1  // 1 = original run
        // Precedence for the total-attempt ceiling (all in "attempts including the original"):
        //  1. WorkerResult.Retry.attemptCap — most specific, the worker's own per-result cap.
        //  2. Constraints.maxRetries (stamped into metadata, only when >= 0) → N + 1 attempts,
        //     matching Android's "N retries = N+1 runs" contract.
        //  3. DEFAULT_ATTEMPT_CAP — nothing specified.
        val metaMaxRetries = rawMeta[META_MAX_RETRIES]?.toIntOrNull()?.takeIf { it >= 0 }
        val effectiveCap = attemptCap
            ?: metaMaxRetries?.let { it + 1 }
            ?: DEFAULT_ATTEMPT_CAP
        val nextAttempt = currentAttempt + 1

        if (nextAttempt > effectiveCap) {
            Logger.w(
                LogTags.SCHEDULER,
                "Dynamic task '$taskId' exhausted retry budget after $currentAttempt attempt(s) " +
                    "(cap=$effectiveCap, reason=$retryReason). Abandoning."
            )
            fileStorage.deleteTaskMetadata(taskId, periodic = false)
            return
        }

        // Persist updated attempt counter BEFORE re-enqueue so a crash between
        // enqueue and metadata-write can't reset the counter to 1 on the next run.
        val updatedMeta = rawMeta.toMutableMap().apply {
            put(META_ATTEMPT_COUNT, nextAttempt.toString())
        }
        fileStorage.saveTaskMetadata(taskId, updatedMeta, periodic = false)

        try {
            fileStorage.enqueueTask(taskId)
            Logger.i(
                LogTags.SCHEDULER,
                "Dynamic task '$taskId' re-enqueued for attempt $nextAttempt/$effectiveCap " +
                    "(reason=$retryReason)"
            )
        } catch (e: IllegalStateException) {
            // Queue full — drop the retry rather than blow up the whole dispatch loop.
            // The metadata stays around so a future scheduler call can pick it up.
            Logger.w(LogTags.SCHEDULER, "Dynamic task '$taskId' retry skipped: ${e.message}")
        }
    }

    private fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun rescheduleMasterDispatcher() {
        if (NSBundle.mainBundle.bundleIdentifier == null) return

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val request = BGProcessingTaskRequest("kmp_master_dispatcher_task")
            request.earliestBeginDate = NSDate()
            // Individual task network constraints are checked by each worker.
            // false here allows non-network tasks to run opportunistically even without
            // connectivity; workers that need network return Failure and remain in the queue.
            request.requiresNetworkConnectivity = false

            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)
            if (!ok) {
                val err = errorPtr.value
                Logger.e(LogTags.SCHEDULER, "Failed to reschedule master dispatcher: ${err?.localizedDescription}")
            }
        }
    }
}
