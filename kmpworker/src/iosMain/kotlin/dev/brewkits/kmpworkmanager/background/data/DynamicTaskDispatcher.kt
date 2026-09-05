@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class
)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
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
    private val fileStorage: IosFileStorage = IosFileStorage(),
    private val networkStateProvider: IosNetworkStateProvider = DefaultIosNetworkStateProvider()
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

        /**
         * Task metadata key holding [dev.brewkits.kmpworkmanager.background.domain.TaskRequest.deadlineMs]
         * (Unix epoch ms) for a standalone task. Written by `NativeTaskScheduler.enqueue`,
         * read here at dispatch time. Absent means "no deadline".
         */
        internal const val META_DEADLINE_MS = "kmpDeadlineMs"

        /**
         * Task metadata key holding a standalone task's user tags, comma-separated.
         * Read by `NativeTaskScheduler.cancelByTag` to resolve which tasks a tag covers.
         *
         * Comma is a safe separator here because [validateTaskTag] rejects it at the API
         * boundary, so a tag can never contain one.
         */
        internal const val META_TAGS = "kmpTags"

        /**
         * Task metadata key holding [dev.brewkits.kmpworkmanager.background.domain.Constraints.requiresUnmeteredNetwork]
         * for a standalone task. BGTaskScheduler has no Wi-Fi-only OS-level flag, so this is
         * checked at dispatch time via [StandaloneConstraintGuard] instead. Only written when
         * `true`.
         */
        internal const val META_REQUIRES_UNMETERED_NETWORK = "kmpRequiresUnmeteredNetwork"

        /**
         * Task metadata key holding whether
         * [dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW]
         * is set on a standalone task. Only written when present.
         */
        internal const val META_REQUIRES_BATTERY_NOT_LOW = "kmpRequiresBatteryNotLow"

        /**
         * Task metadata key holding whether
         * [dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.ALLOW_LOW_BATTERY]
         * is set on a standalone task — overrides [META_REQUIRES_BATTERY_NOT_LOW] for that task.
         * Only written when present.
         */
        internal const val META_ALLOW_LOW_BATTERY = "kmpAllowLowBattery"

        /**
         * Task metadata keys carrying
         * [dev.brewkits.kmpworkmanager.background.domain.Constraints.backoffPolicy]/`backoffDelayMs`
         * for a standalone task's retry timing. Only written when non-default.
         */
        internal const val META_BACKOFF_POLICY = "kmpBackoffPolicy"
        internal const val META_BACKOFF_DELAY_MS = "kmpBackoffDelayMs"

        /**
         * Task metadata key holding the epoch-ms floor before which a retried standalone task
         * must not be re-executed, computed from [META_BACKOFF_POLICY]/[META_BACKOFF_DELAY_MS].
         * Checked in [executePendingTasks] before dispatch.
         */
        internal const val META_NEXT_RETRY_EARLIEST_MS = "kmpNextRetryEarliestMs"

        // Hard ceiling when the worker did not specify [WorkerResult.Retry.attemptCap] and
        // the caller set no Constraints.maxRetries. Mirrors WorkManager's default backoff
        // retry budget so behaviour matches the Android side. Same magnitude as
        // ChainProgress.DEFAULT_MAX_RETRIES.
        internal const val DEFAULT_ATTEMPT_CAP = 5

        // NSDate has no timeIntervalSince1970 constructor in Kotlin/Native cinterop — only
        // timeIntervalSinceReferenceDate (Apple's epoch: 2001-01-01). This is the fixed
        // offset between the two epochs, used to convert an epoch-ms backoff floor into an
        // NSDate. Mirrors NativeTaskScheduler's private constant of the same value.
        private const val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0
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

            // Deadline guard (P1-C) for standalone tasks. BGTaskScheduler is opportunistic:
            // `earliestBeginDate` is a floor, never a ceiling, so a task can surface hours
            // after the window its data was useful in. Checking at dispatch time — after the
            // OS finally woke us — is the only place the real elapsed delay is known.
            //
            // Mirrors Android's BaseKmpWorker deadline guard, and like it, drops the task
            // instead of retrying: a missed deadline cannot be un-missed by running later.
            val deadlineMs = meta.rawMeta?.get(META_DEADLINE_MS)?.toLongOrNull()
            if (deadlineMs != null && currentTimeMs() > deadlineMs) {
                Logger.w(
                    LogTags.SCHEDULER,
                    "⏰ Dynamic task '$taskId' SKIPPED — deadline exceeded " +
                        "(deadline=${deadlineMs}ms, now=${currentTimeMs()}ms). " +
                        "Dropping instead of executing to avoid stale-data side-effects."
                )
                TaskEventManager.emit(
                    TaskCompletionEvent(
                        taskName = meta.workerClassName,
                        success = false,
                        message = "Deadline exceeded — task skipped"
                    )
                )
                fileStorage.deleteTaskMetadata(taskId, periodic = false)
                continue
            }

            // Backoff guard — one-time tasks only (see handleOneTimeResult). Re-queues without
            // executing so a retry actually waits out its computed delay instead of firing on
            // the very next opportunistic BGTask wake.
            if (!meta.isPeriodic) {
                val nextRetryEarliestMs = meta.rawMeta?.get(META_NEXT_RETRY_EARLIEST_MS)?.toLongOrNull()
                if (nextRetryEarliestMs != null && currentTimeMs() < nextRetryEarliestMs) {
                    Logger.d(
                        LogTags.SCHEDULER,
                        "Dynamic task '$taskId' still within backoff window " +
                            "(${nextRetryEarliestMs - currentTimeMs()}ms left) — deferring"
                    )
                    try {
                        fileStorage.enqueueTask(taskId)
                    } catch (e: IllegalStateException) {
                        Logger.w(LogTags.SCHEDULER, "Dynamic task '$taskId' backoff re-queue skipped: ${e.message}")
                    }
                    continue
                }
            }

            // Constraint guard for standalone tasks — BGTaskScheduler has no Wi-Fi-only or
            // Low-Power-Mode flag, so these are enforced here instead of at OS-request time.
            // Periodic tasks only warn (not blocked): unlike one-time tasks they have no
            // "defer without breaking the schedule" path today, and their OS-level
            // requiresNetworkConnectivity/requiresExternalPower flags already cover the common
            // case at submission time.
            val violation = StandaloneConstraintGuard.violationReason(meta.rawMeta, networkStateProvider)
            if (violation != null) {
                if (meta.isPeriodic) {
                    Logger.w(LogTags.SCHEDULER, "Periodic task '$taskId' running despite: $violation")
                } else {
                    // Deferred, not failed: an unmet constraint means the task never got a
                    // chance to run at all, so it must NOT consume retry budget. Re-enqueue
                    // exactly like the backoff guard above — same reasoning, same pattern —
                    // instead of routing through handleOneTimeResult, which increments
                    // META_ATTEMPT_COUNT and would eventually delete the task (after
                    // DEFAULT_ATTEMPT_CAP dispatcher wakes) purely because the constraint kept
                    // being unmet, e.g. a Wi-Fi-only task during a long commute on cellular.
                    Logger.w(LogTags.SCHEDULER, "Dynamic task '$taskId' deferred — $violation")
                    try {
                        fileStorage.enqueueTask(taskId)
                    } catch (e: IllegalStateException) {
                        Logger.w(LogTags.SCHEDULER, "Dynamic task '$taskId' constraint re-queue skipped: ${e.message}")
                    }
                    continue
                }
            }

            Logger.i(LogTags.SCHEDULER, "DynamicTaskDispatcher: Executing '$taskId'")

            try {
                // SingleTaskExecutor only catches IllegalArgumentException around
                // workerFactory.createWorker() (its documented "throw IllegalArgumentException
                // only" contract — see IosWorker.kt). A host app's factory implementation can
                // throw anything else (e.g. an uninitialized-DI NullPointerException), which
                // would otherwise propagate straight to this function's outer catch below and
                // be swallowed by a bare log line — never reaching handleOneTimeResult (leaving
                // a one-time task orphaned until the 7-day cleanupStaleMetadata sweep) or
                // reschedulePeriodicTask (silently and permanently stopping a periodic task's
                // recurring schedule, with no self-healing until that same 7-day sweep deletes
                // it outright). Converting it to a retryable WorkerResult.Failure here routes it
                // through the same result-handling both branches already have.
                val result = try {
                    singleTaskExecutor.executeTask(meta.workerClassName, meta.inputJson, taskId = taskId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.SCHEDULER,
                        "Dynamic task '$taskId' worker resolution threw unexpectedly — treating as a transient failure",
                        e
                    )
                    WorkerResult.Failure(
                        "Unexpected exception resolving/running worker: ${e.message}",
                        shouldRetry = true
                    )
                }

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
        //
        // Backoff floor: only stamped when the caller actually set a non-default
        // Constraints.backoffPolicy/backoffDelayMs (i.e. META_BACKOFF_POLICY/META_BACKOFF_DELAY_MS
        // is present in metadata — see putStandaloneConstraintMetadata, which only writes these
        // keys for non-default values). Without an explicit request, retries keep the
        // pre-existing behavior: fire on the very next opportunistic BGTask wake, no artificial
        // floor. Unconditionally defaulting to a 30s/EXPONENTIAL floor here would silently
        // change retry timing for every existing caller that never touched backoffPolicy.
        val hasExplicitBackoff = rawMeta.containsKey(META_BACKOFF_POLICY) || rawMeta.containsKey(META_BACKOFF_DELAY_MS)
        val updatedMeta = rawMeta.toMutableMap().apply {
            put(META_ATTEMPT_COUNT, nextAttempt.toString())
            if (hasExplicitBackoff) {
                val backoffPolicy = rawMeta[META_BACKOFF_POLICY]
                    ?.let { runCatching { BackoffPolicy.valueOf(it) }.getOrNull() }
                    ?: BackoffPolicy.EXPONENTIAL
                val backoffDelayMs = rawMeta[META_BACKOFF_DELAY_MS]?.toLongOrNull() ?: 30_000L
                val nextRetryEarliestMs = currentTimeMs() + computeBackoffDelayMs(backoffPolicy, backoffDelayMs, currentAttempt)
                put(META_NEXT_RETRY_EARLIEST_MS, "$nextRetryEarliestMs")
            }
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

    /**
     * Mirrors WorkManager's backoff math (`WorkRequest.setBackoffCriteria`): LINEAR scales the
     * base delay by the attempt number, EXPONENTIAL doubles it each attempt. Capped at 1 hour,
     * the same ceiling WorkManager applies to its own backoff.
     */
    private fun computeBackoffDelayMs(policy: BackoffPolicy, baseDelayMs: Long, attempt: Int): Long {
        val maxDelayMs = 60 * 60 * 1000L
        val delay = when (policy) {
            BackoffPolicy.LINEAR -> baseDelayMs * attempt
            BackoffPolicy.EXPONENTIAL -> baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 20))
        }
        return delay.coerceIn(0L, maxDelayMs)
    }

    private suspend fun rescheduleMasterDispatcher() {
        if (NSBundle.mainBundle.bundleIdentifier == null) return

        // Derive requiresNetworkConnectivity from what's actually still pending, instead of
        // always requesting unconstrained. See docs/ios-dynamic-task-scheduling.md § 5.
        //
        // The dispatcher stays a BGProcessingTaskRequest here (never BGAppRefreshTaskRequest)
        // even when every pending task is light — see the NOTE in
        // NativeTaskScheduler.submitTaskRequest's dynamic-task branch for why that's not safe
        // yet with the current batch executor's per-task timeout budget.
        val summary = fileStorage.getDynamicQueueConstraintSummary()
        val requiresNetwork = summary.allRequireNetwork
        // See DynamicQueueConstraintSummary.allRequireCharging: holds the OS-level flag when
        // every pending task needs charging, instead of relying solely on the opt-in-gated
        // StandaloneConstraintGuard runtime check.
        val requiresCharging = summary.allRequireCharging

        // If every pending task is still within its backoff window, honor the earliest of
        // those floors instead of NSDate() (now) — otherwise the dispatcher wakes
        // immediately, finds nothing runnable (the backoff guard in executePendingTasks
        // re-queues without executing), and re-requests itself again, burning BGTask quota
        // for the whole backoff duration instead of actually waiting it out.
        val earliestBeginDate = summary.earliestBackoffFloorMs
            ?.let { floorMs ->
                val unixSeconds = floorMs / 1000.0
                NSDate(timeIntervalSinceReferenceDate = unixSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS)
            }
            ?: NSDate()

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val request = BGProcessingTaskRequest("kmp_master_dispatcher_task").apply {
                this.earliestBeginDate = earliestBeginDate
                // Individual task network/charging constraints are checked by each worker
                // (StandaloneConstraintGuard) as a fallback. Only require these here at the
                // OS level when EVERY pending task needs them — otherwise false lets
                // non-constrained tasks run opportunistically even without connectivity/power;
                // workers that need it return Failure and remain queued.
                requiresNetworkConnectivity = requiresNetwork
                requiresExternalPower = requiresCharging
            }

            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)
            if (!ok) {
                val err = errorPtr.value
                Logger.e(LogTags.SCHEDULER, "Failed to reschedule master dispatcher: ${err?.localizedDescription}")
            }
        }
    }
}
