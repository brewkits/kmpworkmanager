package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.BGTaskType
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressBus
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryStateUnknown
import platform.UIKit.UIDeviceBatteryStateUnplugged
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN

/**
 * Executes task chains on the iOS platform with batch processing support.
 *
 * Features:
 * - Batch processing: Execute multiple chains in one BGTask invocation
 * - File-based storage for improved performance and thread safety
 * - Timeout protection per task
 * - Comprehensive error handling and logging
 * - Task completion event emission
 * - Memory-safe coroutine scope management
 *
 * **Usage with automatic cleanup:**
 * ```kotlin
 * ChainExecutor(factory, taskType).use { executor ->
 *     executor.executeChainsInBatch()
 * }
 * // Automatically cleaned up after use
 * ```
 *
 * @param workerFactory Factory for creating worker instances
 * @param taskType Type of BGTask (APP_REFRESH or PROCESSING) - determines timeout limits
 */
/**
 * Executes task chains on the iOS platform with batch processing support.
 *
 * **Memory safety — Swift callers MUST use `[weak self]`:**
 * The `onContinuationNeeded` closure is retained by this executor until `close()` is called.
 * If the closure captures a view controller or any object that in turn holds a strong reference
 * to this executor, a retain cycle will prevent both from being deallocated.
 *
 * ```swift
 * // ✅ Correct — capture self weakly
 * let executor = ChainExecutor(
 *     workerFactory: factory,
 *     taskType: .processing,
 *     onContinuationNeeded: { [weak self] in
 *         self?.scheduleNextBGTask()
 *     }
 * )
 *
 * // ❌ Wrong — strong capture creates a retain cycle if self holds executor
 * let executor = ChainExecutor(
 *     workerFactory: factory,
 *     onContinuationNeeded: {
 *         self.scheduleNextBGTask()  // strong capture — leak!
 *     }
 * )
 * ```
 */
class ChainExecutor(
    private val workerFactory: IosWorkerFactory,
    private val taskType: BGTaskType = BGTaskType.PROCESSING,
    onContinuationNeeded: (() -> Unit)? = null
) : AutoCloseable {

    // AtomicInt for lock-free isClosed check in synchronous close().
    // compareAndSet(0, 1) ensures only one caller wins the race, avoiding double-flush.
    private val closedFlag = AtomicInt(0)
    private val closeMutex = Mutex()

    // Stored as var so close() can null it out, breaking any Swift strong-reference cycle.
    // Never read after close() because closedFlag prevents re-entry.
    private var continuationCallback: (() -> Unit)? = onContinuationNeeded

    /** Tracks the async cleanup job started by close() */
    private var closeCleanupJob: Job? = null

    // A-3 note: NativeTaskScheduler creates its own IosFileStorage instance pointing to the
    // same default path. Counter divergence is avoided by having getQueueSize() always read
    // from disk rather than trusting the in-memory counter (see IosFileStorage.getQueueSize).
    private val fileStorage = IosFileStorage()
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    // Thread-safe set to track active chains (prevents duplicate execution)
    private val activeChainsMutex = Mutex()
    private val activeChains = mutableSetOf<String>()

    // AtomicInt for lock-free shutdown flag.
    // Using AtomicInt (not Mutex) is critical: requestShutdown() is called from the iOS
    // BGTask expiration handler which must complete instantly. If all Dispatchers.Default
    // threads are occupied by blocking NSFileCoordinator I/O, a Mutex.withLock suspension
    // would queue behind those blocked calls and never run — causing iOS to kill the process
    // before progress is saved.  An AtomicInt write is always immediate, regardless of
    // thread-pool saturation.
    private val isShuttingDown = AtomicInt(0)

    /**
     * Measured cleanup duration from previous batch execution
     * Used for adaptive time budget calculation
     */
    private var lastCleanupDurationMs: Long = 0L

    /**
     * Timeout for individual tasks within chain
     * - APP_REFRESH: 20 seconds (tight time budget)
     * - PROCESSING: 120 seconds (2 minutes - more generous)
     */
    private val taskTimeout: Long = when (taskType) {
        BGTaskType.APP_REFRESH -> 20_000L
        BGTaskType.PROCESSING -> 120_000L
    }

    /**
     * Maximum time for chain execution
     * - APP_REFRESH: 25 seconds (iOS BGAppRefreshTask ~30s OS limit)
     * - PROCESSING: 300 seconds (5 minutes from typical 5-10 min window)
     */
    private val chainTimeout: Long = when (taskType) {
        BGTaskType.APP_REFRESH -> 25_000L
        BGTaskType.PROCESSING -> 300_000L
    }

    companion object {
        /**
         * Default timeout for individual tasks (20 seconds)
         */
        const val TASK_TIMEOUT_MS = 20_000L

        /**
         * Default maximum time for chain execution (50 seconds)
         */
        const val CHAIN_TIMEOUT_MS = 50_000L

        /**
         * Time allowed for saving progress after shutdown signal
         */
        const val SHUTDOWN_GRACE_PERIOD_MS = 5_000L
    }

    /**
     * This should be called when iOS signals BGTask expiration.
     *
     * **What it does**:
     * - Sets shutdown flag to stop accepting new chains
     * - Cancels the coroutine scope to interrupt running chains
     * - Running chains will catch CancellationException and save progress
     * - Waits for grace period to allow progress saving
     *
     * **Usage in Swift/Obj-C**:
     * ```swift
     * BGTaskScheduler.shared.register(forTaskWithIdentifier: id) { task in
     *     task.expirationHandler = {
     *         chainExecutor.requestShutdown() // Call this!
     *     }
     *     // ... execute chains ...
     * }
     * ```
     */
    suspend fun requestShutdown() {
        checkNotClosed()

        // compareAndSet is atomic and non-blocking — safe to call from iOS expiration handler
        // even when all coroutine threads are blocked on NSFileCoordinator I/O.
        if (!isShuttingDown.compareAndSet(0, 1)) {
            Logger.w(LogTags.CHAIN, "Shutdown already in progress")
            return
        }

        Logger.w(LogTags.CHAIN, "🛑 Graceful shutdown requested - cancelling active chains")

        // Cancel all running chains — NonCancellable progress saves in executeChain will complete
        job.cancelChildren()

        // Flush buffered progress after cancellation
        fileStorage.flushNow()
        Logger.d(LogTags.CHAIN, "Progress buffer flushed during shutdown")

        Logger.i(LogTags.CHAIN, "Graceful shutdown complete. Active chains: ${activeChains.size}")
    }

    /**
     * Resets shutdown state so the executor can process a new BGTask batch.
     * Call this at the start of each new BGTask handler, before executeChainsInBatch().
     */
    fun resetShutdownState() {
        checkNotClosed()
        isShuttingDown.compareAndSet(1, 0)
        Logger.d(LogTags.CHAIN, "Shutdown state reset")
    }

    /**
     * Returns the current number of chains waiting in the execution queue.
     */
    suspend fun getChainQueueSize(): Int {
        checkNotClosed()
        return fileStorage.getQueueSize()
    }

    /**
     * Execution metrics for monitoring and telemetry
     */
    data class ExecutionMetrics(
        val taskType: BGTaskType,
        val startTime: Long,
        val endTime: Long,
        val duration: Long,
        val chainsAttempted: Int,
        val chainsSucceeded: Int,
        val chainsFailed: Int,
        val wasKilledBySystem: Boolean,
        val timeUsagePercentage: Int,
        val queueSizeRemaining: Int
    )

    /**
     * Result of executing next chain from queue
     */
    enum class NextChainResult {
        /** Chain executed successfully */
        SUCCESS,
        /** Chain executed but failed */
        FAILURE,
        /** Chain was skipped (e.g. deleted by REPLACE policy) */
        SKIPPED,
        /** Queue was empty */
        QUEUE_EMPTY
    }

    /**
     * Calculate adaptive time budget based on measured cleanup duration
     *
     * **Adaptive Strategy:**
     * - Base: 85% of total time (15% buffer)
     * - If cleanup history available: Reserve measured cleanup time + 20% safety buffer
     * - Floor: Never go below 70% to ensure meaningful work time
     *
     * **Why Adaptive?**
     * - Hardcoded 85% doesn't account for cleanup variance across devices
     * - iPhone 8: Cleanup may take 2-3s
     * - iPhone 15 Pro: Cleanup may take 200-300ms
     * - This adapts to device capability automatically
     *
     * @param totalTimeout Total available time
     * @return Conservative time budget for execution (excluding cleanup buffer)
     */
    private fun calculateAdaptiveBudget(totalTimeout: Long): Long {
        // Base budget: 85% of time (backward compatible)
        val baseBudget = (totalTimeout * 0.85).toLong()

        // If we have cleanup history, use measured duration + 20% safety buffer
        if (lastCleanupDurationMs > 0L) {
            val safetyBuffer = (lastCleanupDurationMs * 1.2).toLong()
            val adaptiveBudget = totalTimeout - safetyBuffer

            // Floor: Never go below 70% (ensure meaningful work time)
            val minBudget = (totalTimeout * 0.70).toLong()

            val finalBudget = maxOf(minBudget, adaptiveBudget)

            Logger.d(LogTags.CHAIN, """
                Adaptive budget calculation:
                - Total timeout: ${totalTimeout}ms
                - Last cleanup: ${lastCleanupDurationMs}ms
                - Safety buffer: ${safetyBuffer}ms (120%)
                - Base budget (85%): ${baseBudget}ms
                - Adaptive budget: ${adaptiveBudget}ms
                - Floor (70%): ${minBudget}ms
                - Final budget: ${finalBudget}ms
            """.trimIndent())

            return finalBudget
        }

        // No history: Use base budget
        Logger.d(LogTags.CHAIN, "No cleanup history - using base budget (85%): ${baseBudget}ms")
        return baseBudget
    }

    /**
     * Execute multiple chains from the queue in batch mode.
     * This optimizes iOS BGTask usage by processing as many chains as possible
     * before the OS time limit is reached.
     *
     * **Time-slicing strategy:**
     * - Uses adaptive time budget based on measured cleanup duration
     * - Checks minimum time before each chain
     * - Stops early to prevent system kills
     * - Schedules continuation if queue not empty
     *
     * @param maxChains Maximum number of chains to process (default: 3)
     * @param totalTimeoutMs Total timeout for batch processing (default: dynamic based on taskType)
     * @param deadlineEpochMs Absolute BGTask expiration time in epoch milliseconds.
     *   When provided, the effective timeout is clamped so execution stops before this deadline
     *   (minus a grace period for progress saving).  This correctly accounts for cold-start time
     *   already consumed before this method was invoked.  Prefer this over relying solely on
     *   totalTimeoutMs when calling from an iOS BGTask handler.
     * @return Number of successfully executed chains
     * @throws IllegalStateException if executor is closed
     */
    suspend fun executeChainsInBatch(
        maxChains: Int = 3,
        totalTimeoutMs: Long = chainTimeout,
        deadlineEpochMs: Long? = null
    ): Int {
        checkNotClosed()

        // Check shutdown flag — if set, skip execution (called from expirationHandler or stale state).
        // Callers starting a new BGTask should call resetShutdownState() first to clear stale state
        // from the previous task's expiration.
        if (isShuttingDown.value != 0) {
            Logger.w(LogTags.CHAIN, "Batch execution skipped - shutdown in progress")
            return 0
        }

        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // If an absolute deadline is provided (e.g. BGTask expiration time), use it to
        // compute remaining time.  This naturally accounts for any cold-start overhead
        // already consumed before this method was called.
        val conservativeTimeout = if (deadlineEpochMs != null) {
            val remaining = deadlineEpochMs - startTime - SHUTDOWN_GRACE_PERIOD_MS
            Logger.i(LogTags.CHAIN, "Absolute deadline provided: ${remaining}ms remaining (deadline epoch: $deadlineEpochMs)")
            minOf(remaining, calculateAdaptiveBudget(totalTimeoutMs)).coerceAtLeast(0L)
        } else {
            calculateAdaptiveBudget(totalTimeoutMs)
        }
        val minTimePerChain = taskTimeout // Minimum time needed per chain

        Logger.i(LogTags.CHAIN, """
            Starting batch chain execution:
            - Max chains: $maxChains
            - Total timeout: ${totalTimeoutMs}ms
            - Conservative timeout: ${conservativeTimeout}ms
            - Min time per chain: ${minTimePerChain}ms
            - Task type: $taskType
        """.trimIndent())
        // If the deadline has already passed (e.g. cold start consumed all available time),
        // bail out immediately rather than calling withTimeout(0) which would throw.
        if (conservativeTimeout <= 0L) {
            Logger.w(LogTags.CHAIN, "⏱️ BGTask deadline already expired (conservativeTimeout=${conservativeTimeout}ms). No chains executed.")
            return 0
        }

        // Sort queue by priority before processing — CRITICAL/HIGH chains execute first.
        // Safe to call here because batch execution is single-threaded per BGTask.
        fileStorage.sortQueueByPriority()

        var chainsAttempted = 0
        var chainsSucceeded = 0
        var chainsFailed = 0
        var wasKilledBySystem = false

        try {
            withTimeout(conservativeTimeout) {
                for (iteration in 0 until maxChains) {
                    // Check shutdown flag — AtomicInt read is always immediate, no suspension needed.
                    if (isShuttingDown.value != 0) {
                        Logger.w(LogTags.CHAIN, "Stopping batch execution - shutdown requested")
                        break
                    }

                    val elapsedTime = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                    val remainingTime = conservativeTimeout - elapsedTime

                    if (remainingTime < minTimePerChain) {
                        Logger.w(LogTags.CHAIN, "⏱️ Time-slicing: Insufficient time remaining (${remainingTime}ms < ${minTimePerChain}ms), stopping early to preserve iOS credit score")
                        break
                    }

                    // Execute next chain
                    val result = executeNextChainFromQueue()

                    when (result) {
                        NextChainResult.SUCCESS -> {
                            chainsAttempted++
                            chainsSucceeded++

                            // Check if more chains in queue
                            if (getChainQueueSize() == 0) {
                                Logger.i(LogTags.CHAIN, "Queue empty after ${chainsSucceeded} chains")
                                break
                            }
                        }
                        NextChainResult.FAILURE -> {
                            chainsAttempted++
                            chainsFailed++
                            Logger.w(LogTags.CHAIN, "Chain execution failed, continuing to next")
                        }
                        NextChainResult.SKIPPED -> {
                            Logger.d(LogTags.CHAIN, "Chain skipped (REPLACE policy), continuing to next")
                            // Don't count skipped chains in metrics
                        }
                        NextChainResult.QUEUE_EMPTY -> {
                            Logger.i(LogTags.CHAIN, "Queue empty, stopping batch")
                            break
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.CHAIN, "Batch execution timed out after ${conservativeTimeout}ms")
            wasKilledBySystem = false // We timed out, but controlled
        } catch (e: CancellationException) {
            Logger.w(LogTags.CHAIN, "Batch execution cancelled - graceful shutdown in progress")
            wasKilledBySystem = true
            throw e // Re-throw to propagate cancellation
        }

        // Measure cleanup time for adaptive budget
        val cleanupStartTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Calculate metrics
        val endTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val duration = endTime - startTime
        val timeUsagePercentage = ((duration * 100) / totalTimeoutMs).toInt()
        val queueSizeRemaining = getChainQueueSize()

        val metrics = ExecutionMetrics(
            taskType = taskType,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            chainsAttempted = chainsAttempted,
            chainsSucceeded = chainsSucceeded,
            chainsFailed = chainsFailed,
            wasKilledBySystem = wasKilledBySystem,
            timeUsagePercentage = timeUsagePercentage,
            queueSizeRemaining = queueSizeRemaining
        )

        // Emit metrics event
        emitMetricsEvent(metrics)

        if (queueSizeRemaining > 0 && !wasKilledBySystem) {
            Logger.i(LogTags.CHAIN, "Queue has $queueSizeRemaining chains remaining - continuation needed")
            scheduleNextBGTask()
        }

        // Record cleanup duration for next run
        val cleanupEndTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
        lastCleanupDurationMs = cleanupEndTime - cleanupStartTime

        Logger.i(LogTags.CHAIN, """
            ✅ Batch execution completed:
            - Attempted: $chainsAttempted
            - Succeeded: $chainsSucceeded
            - Failed: $chainsFailed
            - Duration: ${duration}ms (${timeUsagePercentage}% of ${totalTimeoutMs}ms)
            - Cleanup time: ${lastCleanupDurationMs}ms (will be used for adaptive budget next run)
            - Remaining in queue: $queueSizeRemaining
        """.trimIndent())

        return chainsSucceeded
    }

    /**
     * Retrieves the next chain ID from the queue and executes it.
     *
     * @return [NextChainResult] indicating the outcome of the execution attempt.
     * @throws IllegalStateException if executor is closed
     */
    suspend fun executeNextChainFromQueue(): NextChainResult {
        checkNotClosed()

        // 1. Retrieve and remove the next chain ID from the queue (atomic operation)
        val chainId = fileStorage.dequeueChain() ?: run {
            Logger.d(LogTags.CHAIN, "Chain queue is empty, nothing to execute")
            return NextChainResult.QUEUE_EMPTY
        }

        if (fileStorage.isChainDeleted(chainId)) {
            Logger.i(LogTags.CHAIN, "Chain $chainId was deleted (REPLACE policy). Skipping execution...")
            fileStorage.clearDeletedMarker(chainId)
            KmpWorkManagerRuntime.telemetryHook?.onChainSkipped(
                TelemetryHook.ChainSkippedEvent(chainId = chainId, platform = "ios", reason = "REPLACE_POLICY")
            )
            return NextChainResult.SKIPPED
        }

        Logger.i(LogTags.CHAIN, "Dequeued chain $chainId for execution (Remaining: ${fileStorage.getQueueSize()})")

        // 2. Execute the chain and return the result
        val success = executeChain(chainId)
        if (success) {
            Logger.i(LogTags.CHAIN, "Chain $chainId completed successfully")
        } else {
            Logger.e(LogTags.CHAIN, "Chain $chainId failed")
            emitChainFailureEvent(chainId)
        }
        return if (success) NextChainResult.SUCCESS else NextChainResult.FAILURE
    }

    /**
     * Execute a single chain by ID with progress tracking and resume support.
     *
     * This method implements state restoration:
     * - Loads existing progress (if any) to resume from last completed step
     * - Saves progress after each step completes
     * - Handles retry logic with configurable max retries
     * - Cleans up progress files on completion or abandonment
     */
    private suspend fun executeChain(chainId: String): Boolean {
        // 1. Check for duplicate execution and mark as active (thread-safe)
        val isAlreadyActive = activeChainsMutex.withLock {
            if (activeChains.contains(chainId)) {
                true
            } else {
                activeChains.add(chainId)
                Logger.d(LogTags.CHAIN, "Marked chain $chainId as active (Total active: ${activeChains.size})")
                false
            }
        }

        if (isAlreadyActive) {
            Logger.w(LogTags.CHAIN, "⚠️ Chain $chainId is already executing, skipping duplicate")
            return false
        }

        try {
            // 3. Load the chain definition from file storage
            val steps = fileStorage.loadChainDefinition(chainId)
            if (steps == null) {
                Logger.e(LogTags.CHAIN, "No chain definition found for ID: $chainId")
                fileStorage.deleteChainProgress(chainId) // Clean up orphaned progress
                return false
            }

            // 3b. Windowed deadline guard — iOS BGTaskScheduler is opportunistic and cannot
            //     honour the `latest` field of TaskTrigger.Windowed. The system may delay the
            //     BGTask well past the business deadline (e.g. "must sync before 08:00").
            //     Check here at actual execution time and skip stale chains so workers never
            //     produce data the app can no longer use.
            val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
            val windowLatestMs = fileStorage.loadTaskMetadata(chainId, periodic = false)
                ?.get("windowLatest")
                ?.toLongOrNull()
            if (windowLatestMs != null && nowMs > windowLatestMs) {
                Logger.e(
                    LogTags.CHAIN,
                    "⏰ Chain $chainId DEADLINE_EXCEEDED — window closed at " +
                        "${NSDate.dateWithTimeIntervalSince1970(windowLatestMs / 1000.0)}, " +
                        "now=${NSDate.dateWithTimeIntervalSince1970(nowMs / 1000.0)}. " +
                        "Task would produce stale data; skipping execution and cleaning up."
                )
                fileStorage.deleteChainDefinition(chainId)
                fileStorage.deleteChainProgress(chainId)
                fileStorage.deleteTaskMetadata(chainId, periodic = false)
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = "Chain-$chainId",
                        success = false,
                        message = "Deadline exceeded — windowed task ran after its latest time"
                    )
                )
                KmpWorkManagerRuntime.telemetryHook?.onChainSkipped(
                    TelemetryHook.ChainSkippedEvent(chainId = chainId, platform = "ios", reason = "DEADLINE_EXCEEDED")
                )
                return false
            }

            // 4. Load or create progress
            val rawProgress = fileStorage.loadChainProgress(chainId)

            // 4b. Self-healing guard: if progress was absent because it was DELETED by corruption
            //     recovery, refuse to restart any chain that contains a non-idempotent task.
            //     Silently restarting a payment step, for example, would cause a double charge.
            if (rawProgress == null && fileStorage.consumeSelfHealedFlag(chainId)) {
                val nonIdempotentTasks = steps.flatten().filter { !it.isIdempotent }
                if (nonIdempotentTasks.isNotEmpty()) {
                    Logger.e(
                        LogTags.CHAIN,
                        "🚨 Chain $chainId QUARANTINED — progress file was corrupt and deleted, " +
                        "but the chain contains non-idempotent task(s): " +
                        "${nonIdempotentTasks.map { it.workerClassName }}. " +
                        "Restarting from scratch could cause duplicate side-effects (e.g. double charge). " +
                        "Manual intervention required. Chain definition preserved for inspection."
                    )
                    // Leave definition on disk for operator inspection; only remove progress.
                    fileStorage.deleteChainProgress(chainId)
                    return false
                }
                Logger.w(
                    LogTags.CHAIN,
                    "⚠️ Chain $chainId progress was corrupt and deleted. All tasks are idempotent — safe to restart from beginning."
                )
            }

            var progress = rawProgress ?: ChainProgress(
                chainId = chainId,
                totalSteps = steps.size
            )

            // 5. Check if max retries exceeded
            if (progress.hasExceededRetries()) {
                Logger.e(
                    LogTags.CHAIN,
                    "Chain $chainId has exceeded max retries (${progress.retryCount}/${progress.maxRetries}). Abandoning."
                )
                fileStorage.deleteChainDefinition(chainId)
                fileStorage.deleteChainProgress(chainId)
                return false
            }

            // 6. Log resume status
            if (progress.completedSteps.isNotEmpty()) {
                Logger.i(
                    LogTags.CHAIN,
                    "Resuming chain $chainId from step ${progress.getNextStepIndex() ?: "end"} " +
                            "(${progress.getCompletionPercentage()}% complete, ${progress.completedSteps.size}/${steps.size} steps done)"
                )
            } else {
                Logger.d(LogTags.CHAIN, "Executing chain $chainId with ${steps.size} steps")
            }

            // 6b. Warn early if the remaining work cannot fit within a single BGTask window.
            // Example: 3 pending steps × 120s task timeout = 360s > 300s chain timeout.
            // The chain will always time out and never finish unless the task implementation
            // is made faster or the chain is split into smaller pieces.
            val remainingSteps = steps.size - progress.completedSteps.size
            val worstCaseMs = taskTimeout * remainingSteps
            if (worstCaseMs > chainTimeout) {
                Logger.w(
                    LogTags.CHAIN,
                    "⚠️ Chain $chainId: $remainingSteps remaining step(s) × ${taskTimeout}ms task timeout " +
                    "= ${worstCaseMs}ms worst-case > ${chainTimeout}ms chain timeout. " +
                    "Chain will time out and retry until retries are exhausted. " +
                    "Consider splitting the chain or reducing per-task execution time."
                )
            }

            // 7. Execute steps sequentially with timeout protection
            val chainStartMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
            var chainSucceeded = false
            try {
                chainSucceeded = withTimeout(chainTimeout) {
                    var allStepsSucceeded = true
                    for ((index, step) in steps.withIndex()) {
                        // Skip already completed steps
                        if (progress.isStepCompleted(index)) {
                            Logger.d(LogTags.CHAIN, "Skipping already completed step ${index + 1}/${steps.size} for chain $chainId")
                            continue
                        }

                        Logger.i(LogTags.CHAIN, "Executing step ${index + 1}/${steps.size} for chain $chainId (${step.size} tasks)")

                        val (stepSuccess, updatedProgress, stepError) = executeStep(step, index, progress, chainId)
                        progress = updatedProgress
                        if (!stepSuccess) {
                            Logger.e(LogTags.CHAIN, "Step ${index + 1} failed. Updating progress for chain $chainId")

                            // Update progress with failure, retry count, and error message
                            withContext(NonCancellable) {
                                progress = progress.withFailure(index, stepError)
                                fileStorage.saveChainProgress(progress)
                            }

                            // Check if we should abandon this chain
                            if (progress.hasExceededRetries()) {
                                Logger.e(
                                    LogTags.CHAIN,
                                    "Chain $chainId exceeded max retries after step ${index + 1} failure. Abandoning."
                                )
                                fileStorage.deleteChainDefinition(chainId)
                                fileStorage.deleteChainProgress(chainId)
                            }

                            KmpWorkManagerRuntime.telemetryHook?.onChainFailed(
                                TelemetryHook.ChainFailedEvent(
                                    chainId = chainId,
                                    failedStep = index,
                                    platform = "ios",
                                    error = stepError ?: "Unknown failure",
                                    retryCount = progress.retryCount,
                                    willRetry = !progress.hasExceededRetries()
                                )
                            )
                            allStepsSucceeded = false
                            break
                        }

                        // Step succeeded - update progress
                        withContext(NonCancellable) {
                            progress = progress.withCompletedStep(index)
                            fileStorage.saveChainProgress(progress)
                        }
                        Logger.d(
                            LogTags.CHAIN,
                            "Step ${index + 1}/${steps.size} completed for chain $chainId (${progress.getCompletionPercentage()}% complete)"
                        )
                    }
                    allStepsSucceeded
                }
            } catch (e: TimeoutCancellationException) {
                // Chain-level timeout: the aggregate wall-clock time of all steps exceeded
                // chainTimeout. This is different from a single task timeout — progress WAS
                // saved after each completed step, so we can resume.
                //
                // We re-enqueue (not just return false) because the chain was already dequeued
                // at the top of executeNextChainFromQueue. Without re-enqueue it would be
                // silently lost — definition and progress files remain on disk but nothing
                // would ever trigger execution again.
                //
                // We also increment retryCount to prevent infinite loops: a chain whose steps
                // individually fit within taskTimeout but whose total time always exceeds
                // chainTimeout would loop forever without this guard.
                val failedStep = progress.getNextStepIndex() ?: (steps.size - 1)
                withContext(NonCancellable) {
                    progress = progress.withFailure(failedStep, "Chain timed out (${chainTimeout}ms)")
                    fileStorage.saveChainProgress(progress)
                }

                if (progress.hasExceededRetries()) {
                    Logger.e(
                        LogTags.CHAIN,
                        "Chain $chainId abandoned — timed out ${progress.retryCount} time(s). " +
                        "Each attempt exceeds the ${chainTimeout}ms chain budget. " +
                        "Consider splitting the chain or reducing task execution time."
                    )
                    fileStorage.deleteChainDefinition(chainId)
                    fileStorage.deleteChainProgress(chainId)
                } else {
                    fileStorage.enqueueChain(chainId)
                    Logger.i(
                        LogTags.CHAIN,
                        "Chain $chainId timed out after ${chainTimeout}ms — re-queued for retry " +
                        "${progress.retryCount}/${progress.maxRetries} " +
                        "(completed ${progress.completedSteps.size}/${steps.size} steps)"
                    )
                }
                KmpWorkManagerRuntime.telemetryHook?.onChainFailed(
                    TelemetryHook.ChainFailedEvent(
                        chainId = chainId,
                        failedStep = progress.getNextStepIndex() ?: (steps.size - 1),
                        platform = "ios",
                        error = "Chain timed out (${chainTimeout}ms)",
                        retryCount = progress.retryCount,
                        willRetry = !progress.hasExceededRetries()
                    )
                )
                return false
            } catch (e: CancellationException) {
                Logger.w(LogTags.CHAIN, "🛑 Chain $chainId cancelled due to graceful shutdown")

                val nextStep = progress.getNextStepIndex()
                Logger.i(
                    LogTags.CHAIN,
                    "Chain $chainId progress saved (${progress.getCompletionPercentage()}% complete). " +
                    "Will resume from step $nextStep on next BGTask execution."
                )

                // Re-queue the chain for next execution
                fileStorage.enqueueChain(chainId)
                Logger.i(LogTags.CHAIN, "Re-queued chain $chainId for resumption")

                // Schedule next BGTask to resume this chain
                scheduleNextBGTask()

                return false
            }

            // 8. Only clean up on successful completion
            if (!chainSucceeded) {
                return false
            }

            // Flush buffered progress before cleanup
            fileStorage.flushNow()

            fileStorage.deleteChainDefinition(chainId)
            fileStorage.deleteChainProgress(chainId)
            Logger.i(LogTags.CHAIN, "Chain $chainId completed all ${steps.size} steps successfully")
            KmpWorkManagerRuntime.telemetryHook?.onChainCompleted(
                TelemetryHook.ChainCompletedEvent(
                    chainId = chainId,
                    totalSteps = steps.size,
                    platform = "ios",
                    durationMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - chainStartMs
                )
            )
            return true

        } finally {
            // 9. Always remove from active set (even on failure/timeout) - thread-safe
            // Flush buffered progress before removing from active set
            // CRITICAL FIX: Wrap flushNow() in try-catch to guarantee cleanup
            try {
                fileStorage.flushNow()
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Failed to flush progress in finally block for chain $chainId", e)
                // Continue to cleanup even if flush fails
            }

            // MUST execute even if flushNow() fails - prevents chain leak
            activeChainsMutex.withLock {
                activeChains.remove(chainId)
                Logger.d(LogTags.CHAIN, "Removed chain $chainId from active set (Remaining active: ${activeChains.size})")
            }
        }
    }

    /**
     * Execute all tasks in a step (parallel execution).
     *
     * Tasks that already completed in a previous attempt (recorded in progress)
     * are skipped, making retry of partially-failed parallel steps idempotent.
     *
     * **Per-task progress persistence (intentional design):**
     * Each task saves progress to the buffer immediately after it succeeds — not after
     * the entire step completes. This preserves partial progress on mid-step crashes:
     * if 3 out of 5 parallel tasks complete and the app is killed, only the 2 pending
     * tasks are retried on resume rather than all 5.
     *
     * **Why [progressMutex] contention is minimal:**
     * [fileStorage.saveChainProgress] writes to a debounced in-memory buffer (100ms TTL)
     * rather than calling NSFileCoordinator directly. Each lock hold is ~1ms regardless
     * of the number of parallel tasks, so N concurrent completions serialise through
     * the mutex in ~N ms total — negligible compared to typical task durations (100ms–60s).
     * The physical I/O is batched: N saves in a 100ms window = 1 actual disk write.
     *
     * @return Triple of (stepSucceeded, updatedProgress, firstErrorMessage?)
     */
    private suspend fun executeStep(
        tasks: List<TaskRequest>,
        stepIndex: Int,
        progress: ChainProgress,
        chainId: String
    ): Triple<Boolean, ChainProgress, String?> {
        if (tasks.isEmpty()) return Triple(true, progress, null)

        var currentProgress = progress
        // Shared across all async blocks launched below — do not extract into a separate function.
        val progressMutex = Mutex()

        // Each async block returns Pair(success, errorMessage?)
        val results: List<Pair<Boolean, String?>> = coroutineScope {
            tasks.mapIndexed { taskIndex, task ->
                async {
                    // Skip tasks that already succeeded in a previous attempt.
                    // Safety: this read of `currentProgress` is intentionally unguarded.
                    // Each block checks only its OWN taskIndex; a sibling's in-flight completion
                    // cannot make this block's taskIndex appear completed.  The only source of
                    // a true positive here is a completion persisted in a *previous* run, which
                    // is already present in the initial `progress` value before any sibling runs.
                    if (currentProgress.isTaskInStepCompleted(stepIndex, taskIndex)) {
                        Logger.d(
                            LogTags.CHAIN,
                            "Skipping already-completed task $taskIndex in step $stepIndex (${task.workerClassName})"
                        )
                        return@async Pair(true, null)
                    }

                    val result = executeTask(task, chainId, stepIndex, progress.retryCount)
                    val success = result is WorkerResult.Success
                    if (success) {
                        // Protect progress save from cancellation
                        withContext(NonCancellable) {
                            progressMutex.withLock {
                                currentProgress = currentProgress.withCompletedTaskInStep(stepIndex, taskIndex)
                                fileStorage.saveChainProgress(currentProgress)
                            }
                        }
                    }
                    val errorMessage = if (!success) (result as? WorkerResult.Failure)?.message else null
                    Pair(success, errorMessage)
                }
            }.awaitAll()
        }

        val allSucceeded = results.all { it.first }
        if (!allSucceeded) {
            Logger.w(LogTags.CHAIN, "Step $stepIndex had ${results.count { !it.first }} failed task(s) out of ${tasks.size}")
        }
        val firstError = results.firstOrNull { !it.first }?.second
        return Triple(allSucceeded, currentProgress, firstError)
    }

    /**
     * Returns `true` when the current network path is WWAN (cellular / mobile data).
     *
     * Uses the synchronous `SCNetworkReachability` API — safe to call from any thread
     * including background coroutine dispatchers. Deprecated on iOS 17 but still
     * functional; replace with `NWPathMonitor` when Kotlin/Native gains async interop.
     *
     * Returns `false` (conservative / allow execution) on any error so a failure to
     * detect the network type does not silently block legitimate work.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun isNetworkCellular(): Boolean {
        val reachability = SCNetworkReachabilityCreateWithName(null, "1.1.1.1") ?: return false
        return try {
            memScoped {
                val flags = alloc<SCNetworkReachabilityFlagsVar>()
                SCNetworkReachabilityGetFlags(reachability, flags.ptr) &&
                    (flags.value and kSCNetworkReachabilityFlagsIsWWAN) != 0u
            }
        } catch (e: Exception) {
            Logger.w(LogTags.CHAIN, "isNetworkCellular() check failed — assuming non-cellular: ${e.message}")
            false
        } finally {
            CFRelease(reachability)
        }
    }

    /**
     * Execute a single task with timeout protection and detailed logging.
     *
     * [Worker.close] is called in a `finally` block under [NonCancellable] so resource
     * cleanup is guaranteed regardless of success, failure, timeout, or cancellation.
     */
    private suspend fun executeTask(
        task: TaskRequest,
        chainId: String? = null,
        stepIndex: Int? = null,
        retryCount: Int = 0
    ): WorkerResult {
        Logger.d(LogTags.CHAIN, "▶️ Starting task: ${task.workerClassName} (timeout: ${taskTimeout}ms)")

        // Unmetered-network guard: iOS BGTaskScheduler has no Wi-Fi-only constraint.
        // We enforce it here at execution time to prevent silent cellular data usage.
        // The Constraints KDoc says "iOS: Not supported, falls back to requiresNetwork" —
        // this check makes the fallback explicit: retry instead of running on cellular.
        if (task.constraints?.requiresUnmeteredNetwork == true && isNetworkCellular()) {
            Logger.w(
                LogTags.CHAIN,
                "⚠️ Task ${task.workerClassName} requires unmetered network but cellular is active — " +
                    "returning Failure(shouldRetry=true). Will be retried on next BGTask execution."
            )
            return WorkerResult.Failure(
                message = "Requires unmetered (Wi-Fi) network but cellular is active",
                shouldRetry = true
            )
        }

        val worker = workerFactory.createWorker(task.workerClassName)
        if (worker == null) {
            Logger.e(LogTags.CHAIN, "❌ Could not create worker for ${task.workerClassName}")
            return WorkerResult.Failure("Worker not found: ${task.workerClassName}")
        }

        // Battery guard: defer task when battery is critically low and not charging.
        // Protects device battery from drain during background execution.
        val minBattery = KmpWorkManagerRuntime.minBatteryLevelPercent
        if (minBattery > 0) {
            UIDevice.currentDevice().batteryMonitoringEnabled = true
            val batteryFloat = UIDevice.currentDevice().batteryLevel
            val batteryState = UIDevice.currentDevice().batteryState
            val isCharging = batteryState != UIDeviceBatteryStateUnplugged &&
                batteryState != UIDeviceBatteryStateUnknown
            val batteryPct = if (batteryFloat >= 0f) (batteryFloat * 100).toInt() else -1
            if (batteryPct in 0 until minBattery && !isCharging) {
                Logger.w(
                    LogTags.CHAIN,
                    "🔋 Task ${task.workerClassName} deferred — battery at ${batteryPct}% " +
                        "(min: ${minBattery}%, not charging)"
                )
                return WorkerResult.Failure(
                    message = "Battery too low (${batteryPct}% < ${minBattery}%)",
                    shouldRetry = true
                )
            }
        }

        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        KmpWorkManagerRuntime.telemetryHook?.onTaskStarted(
            TelemetryHook.TaskStartedEvent(
                taskName = task.workerClassName,
                chainId = chainId,
                stepIndex = stepIndex,
                platform = "ios",
                startedAtMs = startTime
            )
        )

        try {
            return try {
                withTimeout(taskTimeout) {
                    val result = worker.doWork(task.inputJson)
                    val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                    val percentage = (duration * 100 / taskTimeout).toInt()

                    if (duration > taskTimeout * 0.8) {
                        Logger.w(LogTags.CHAIN, "⚠️ Task ${task.workerClassName} used ${duration}ms / ${taskTimeout}ms (${percentage}%) - approaching timeout!")
                    }

                    when (result) {
                        is WorkerResult.Success -> {
                            val message = result.message ?: "Task succeeded in ${duration}ms"
                            Logger.d(LogTags.CHAIN, "✅ Task ${task.workerClassName} - $message (${percentage}%)")
                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = task.workerClassName,
                                    success = true,
                                    message = message,
                                    outputData = result.data
                                )
                            )
                            KmpWorkManagerRuntime.telemetryHook?.onTaskCompleted(
                                TelemetryHook.TaskCompletedEvent(
                                    taskName = task.workerClassName,
                                    chainId = chainId,
                                    stepIndex = stepIndex,
                                    platform = "ios",
                                    success = true,
                                    durationMs = duration
                                )
                            )
                            result
                        }
                        is WorkerResult.Failure -> {
                            Logger.w(LogTags.CHAIN, "❌ Task ${task.workerClassName} failed: ${result.message} (${duration}ms)")
                            TaskEventBus.emit(
                                TaskCompletionEvent(
                                    taskName = task.workerClassName,
                                    success = false,
                                    message = result.message,
                                    outputData = null
                                )
                            )
                            KmpWorkManagerRuntime.telemetryHook?.onTaskCompleted(
                                TelemetryHook.TaskCompletedEvent(
                                    taskName = task.workerClassName,
                                    chainId = chainId,
                                    stepIndex = stepIndex,
                                    platform = "ios",
                                    success = false,
                                    durationMs = duration,
                                    errorMessage = result.message
                                )
                            )
                            KmpWorkManagerRuntime.telemetryHook?.onTaskFailed(
                                TelemetryHook.TaskFailedEvent(
                                    taskName = task.workerClassName,
                                    chainId = chainId,
                                    stepIndex = stepIndex,
                                    platform = "ios",
                                    error = result.message ?: "Unknown failure",
                                    durationMs = duration,
                                    retryCount = retryCount
                                )
                            )
                            result
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                Logger.e(LogTags.CHAIN, "⏱️ Task ${task.workerClassName} timed out after ${duration}ms (limit: ${taskTimeout}ms)")
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = task.workerClassName,
                        success = false,
                        message = "⏱️ Timeout after ${duration}ms",
                        outputData = null
                    )
                )
                KmpWorkManagerRuntime.telemetryHook?.onTaskFailed(
                    TelemetryHook.TaskFailedEvent(
                        taskName = task.workerClassName,
                        chainId = chainId,
                        stepIndex = stepIndex,
                        platform = "ios",
                        error = "Timeout after ${duration}ms",
                        durationMs = duration,
                        retryCount = retryCount
                    )
                )
                WorkerResult.Failure("Timeout after ${duration}ms")
            } catch (e: CancellationException) {
                // Must re-throw — swallowing breaks coroutine cancellation protocol.
                throw e
            } catch (e: Exception) {
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                Logger.e(LogTags.CHAIN, "💥 Task ${task.workerClassName} threw exception after ${duration}ms", e)
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = task.workerClassName,
                        success = false,
                        message = "💥 Exception: ${e.message}",
                        outputData = null
                    )
                )
                KmpWorkManagerRuntime.telemetryHook?.onTaskFailed(
                    TelemetryHook.TaskFailedEvent(
                        taskName = task.workerClassName,
                        chainId = chainId,
                        stepIndex = stepIndex,
                        platform = "ios",
                        error = e.message ?: "Unknown exception",
                        durationMs = duration,
                        retryCount = retryCount
                    )
                )
                WorkerResult.Failure("Exception: ${e.message}")
            }
        } finally {
            // Guarantee resource cleanup regardless of outcome (success, failure, timeout, cancel).
            // NonCancellable ensures this runs even during coroutine cancellation.
            withContext(NonCancellable) {
                try {
                    worker.close()
                } catch (e: Exception) {
                    Logger.w(LogTags.CHAIN, "Worker.close() threw for ${task.workerClassName}: ${e.message}")
                }
                // Release the per-task throttle entry so the map doesn't grow unbounded.
                TaskProgressBus.clearThrottle(task.workerClassName)
            }
        }
    }

    /**
     * Emit chain failure event to UI
     */
    private fun emitChainFailureEvent(chainId: String) {
        // This prevents unbounded scope creation and ensures proper lifecycle management
        coroutineScope.launch(Dispatchers.Main) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Chain-$chainId",
                    success = false,
                    message = "❌ Chain execution failed"
                )
            )
        }
    }

    /**
     * Emit execution metrics event for monitoring
     */
    private fun emitMetricsEvent(metrics: ExecutionMetrics) {
        coroutineScope.launch(Dispatchers.Main) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "BatchExecution-${metrics.taskType}",
                    success = !metrics.wasKilledBySystem,
                    message = """
                        Completed: ${metrics.chainsSucceeded}/${metrics.chainsAttempted}
                        Duration: ${metrics.duration}ms (${metrics.timeUsagePercentage}%)
                        Remaining: ${metrics.queueSizeRemaining}
                    """.trimIndent()
                )
            )
        }

        // Analytics/Telemetry Integration Point
        // Apps can add custom analytics tracking here by:
        // 1. Listening to TaskEventBus events
        // 2. Extending ChainExecutor and overriding this method
        // 3. Using ExecutionMetrics data for reporting:
        //    - metrics.taskType, metrics.duration
        //    - metrics.chainsSucceeded, metrics.chainsFailed
        //    - metrics.timeUsagePercentage, metrics.wasKilledBySystem
        //
        // Example with custom analytics:
        // Analytics.track("bgwork_batch_complete", mapOf(
        //     "task_type" to metrics.taskType.name,
        //     "chains_succeeded" to metrics.chainsSucceeded,
        //     "chains_failed" to metrics.chainsFailed,
        //     "duration_ms" to metrics.duration,
        //     "time_usage_pct" to metrics.timeUsagePercentage
        // ))
    }

    /**
     * Schedule next BGTask for continuation
     *
     * Now calls the onContinuationNeeded callback if provided.
     *
     * **Usage from Swift:**
     * ```swift
     * let executor = ChainExecutor(
     *     workerFactory: factory,
     *     taskType: .processing,
     *     onContinuationNeeded: {
     *         let request = BGProcessingTaskRequest(identifier: "chain_executor")
     *         request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
     *         try? BGTaskScheduler.shared.submit(request)
     *     }
     * )
     * ```
     */
    private fun scheduleNextBGTask() {
        Logger.i(LogTags.CHAIN, "📅 Continuation needed: Queue has remaining chains")

        val callback = continuationCallback
        if (callback != null) {
            Logger.d(LogTags.CHAIN, "Invoking continuation callback to schedule next BGTask")
            callback.invoke()
        } else {
            Logger.w(LogTags.CHAIN, """
                ⚠️ No continuation callback provided!

                Chains remain in queue but no BGTask will be scheduled.
                Provide onContinuationNeeded callback when creating ChainExecutor:

                Swift example:
                let executor = ChainExecutor(
                    workerFactory: factory,
                    taskType: .processing,
                    onContinuationNeeded: {
                        let request = BGProcessingTaskRequest(identifier: "chain_executor")
                        request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
                        try? BGTaskScheduler.shared.submit(request)
                    }
                )
            """.trimIndent())
        }

        // Emit event to notify that continuation is needed
        coroutineScope.launch(Dispatchers.Main) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ContinuationNeeded",
                    success = true,
                    message = "Queue has remaining chains - schedule next BGTask"
                )
            )
        }
    }

    /**
     * Implement Closeable interface
     *
     * This method ensures that:
     * - Coroutine scope is cancelled
     * - Resources are properly released
     * - Subsequent calls are no-ops
     * - Thread-safe with mutex protection
     *
     * Non-blocking close to prevent deadlocks. Progress flush happens
     * asynchronously. For guaranteed cleanup, use closeAsync() instead.
     */
    override fun close() {
        // compareAndSet is atomic → only one concurrent caller wins
        if (!closedFlag.compareAndSet(0, 1)) {
            Logger.d(LogTags.CHAIN, "ChainExecutor already closed")
            return
        }

        Logger.d(LogTags.CHAIN, "Closing ChainExecutor")

        // Null the callback to break any Swift strong-reference cycle before the
        // coroutine scope is cancelled and the executor becomes unreachable.
        continuationCallback = null

        // Cancel all running coroutines first (non-blocking)
        job.cancel()

        // Launch async cleanup on a separate scope to avoid blocking
        // Tracked so tests and closeAsync() can await completion
        closeCleanupJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                // Flush buffered progress before fully closing
                fileStorage.flushNow()
                Logger.d(LogTags.CHAIN, "Progress buffer flushed during close")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Error flushing progress during close", e)
            } finally {
                // Close fileStorage to cancel its backgroundScope
                fileStorage.close()
            }
            Logger.i(LogTags.CHAIN, "ChainExecutor closed successfully")
        }
    }

    /**
     * Async version of close() that guarantees cleanup completion.
     * Use this when you need to ensure all resources are flushed before proceeding.
     *
     * Recommended for critical cleanup paths (app shutdown, etc.)
     */
    suspend fun closeAsync() {
        closeMutex.withLock {
            if (!closedFlag.compareAndSet(0, 1)) {
                Logger.d(LogTags.CHAIN, "ChainExecutor already closed")
                return
            }

            Logger.d(LogTags.CHAIN, "Closing ChainExecutor (async)")

            continuationCallback = null  // Break Swift retain cycle

            // Cancel all running coroutines
            job.cancel()

            try {
                // Flush buffered progress before closing
                fileStorage.flushNow()
                Logger.d(LogTags.CHAIN, "Progress buffer flushed during close")
            } finally {
                // Close fileStorage to cancel its backgroundScope
                fileStorage.close()
            }

            Logger.i(LogTags.CHAIN, "ChainExecutor closed successfully")
        }
    }

    /**
     * Check if executor is closed, throw if it is
     */
    private fun checkNotClosed() {
        if (closedFlag.value != 0) {
            throw IllegalStateException("ChainExecutor is closed and cannot be used")
        }
    }

    /**
     * Cleanup coroutine scope (call when executor is no longer needed)
     *
     * @deprecated Use close() or .use {} pattern instead
     */
    @Deprecated(
        message = "Use close() or .use {} pattern instead",
        replaceWith = ReplaceWith("close()"),
        level = DeprecationLevel.WARNING
    )
    fun cleanup() {
        close()
    }
}

/**
 * Extension function for using AutoCloseable with automatic cleanup
 */
inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when (exception) {
            null -> close()
            else -> try {
                close()
            } catch (closeException: Throwable) {
                // Suppressed exception
            }
        }
    }
}
