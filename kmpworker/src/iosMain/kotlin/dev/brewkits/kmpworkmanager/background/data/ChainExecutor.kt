@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.BGTaskType
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressBus
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.TaskProgressEvent
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.ProgressListener
import dev.brewkits.kmpworkmanager.background.domain.WorkerProgress
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.Foundation.NSProcessInfo
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
 * - Timeout protection per task (APP_REFRESH: 20s, PROCESSING: 120s)
 * - Comprehensive error handling and logging
 * - Task completion event emission
 * - Memory-safe coroutine scope management (WeakReference for Swift closures)
 *
 * **Usage:**
 * ```kotlin
 * ChainExecutor(factory, taskType).use { executor ->
 *     executor.executeChainsInBatch()
 * }
 * ```
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
    onContinuationNeeded: (() -> Unit)? = null,
    internal val fileStorage: IosFileStorage = IosFileStorage(),
    internal val networkStateProvider: IosNetworkStateProvider = DefaultIosNetworkStateProvider()
) {

    // AtomicInt for lock-free isClosed check in synchronous close().
    // compareAndSet(0, 1) ensures only one caller wins the race, avoiding double-flush.
    private val closedFlag = AtomicInt(0)
    private val closeMutex = Mutex()

    // Stored as WeakReference to break potential Swift retain cycles.
    // If the closure captures `self` and the Swift object holds a strong reference to this
    // executor, a strong reference here would form a cycle. WeakReference allows the Swift
    // object to be deallocated even while the executor is alive.
    // Callers: use `[weak self]` in Swift closures passed as `onContinuationNeeded`.
    private val weakContinuationCallback = onContinuationNeeded?.let { kotlin.native.ref.WeakReference(it) }

    // Whether a continuation callback was ever registered. Used to distinguish "callback never
    // provided" (acceptable, developer opted out) from "callback provided but owner GC'd"
    // (a data-availability regression that deserves an ERROR log).
    private val hasContinuationCallback = onContinuationNeeded != null

    // A-3 note: NativeTaskScheduler creates its own IosFileStorage instance pointing to the
    // same default path. Counter divergence is avoided by having getQueueSize() always read
    // from disk rather than trusting the in-memory counter (see IosFileStorage.getQueueSize).
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    // Thread-safe set to track active chains (prevents duplicate execution)
    private val activeChainsMutex = Mutex()
    // Maps chainId → timestamp (ms) when it was marked active, for stale-lock detection.
    private val activeChains = mutableMapOf<String, Long>()

    // Maps chainId → the coroutine Job currently executing that chain.
    // Used by REPLACE policy: when a new execution finds isAlreadyActive, it cancels the old
    // Job directly so it releases the activeChains lock and the replacement can run immediately.
    private val activeChainJobsMutex = Mutex()
    private val activeChainJobs = mutableMapOf<String, Job>()

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
    internal val taskTimeout: Long = when (taskType) {
        BGTaskType.APP_REFRESH -> 20_000L
        BGTaskType.PROCESSING -> 120_000L
    }

    /**
     * Maximum time for chain execution
     * - APP_REFRESH: 25 seconds (iOS BGAppRefreshTask ~30s OS limit)
     * - PROCESSING: 300 seconds (5 minutes from typical 5-10 min window)
     */
    internal val chainTimeout: Long = when (taskType) {
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

        /**
         * Active-chain lock age after which it is considered stale and evicted.
         *
         * A lock older than this threshold indicates the process was killed mid-execution
         * (BGTask expiration, OOM) without reaching the finally block that clears the lock.
         * Evicting the stale lock allows the chain to run again on the next BGTask invocation
         * instead of being silently skipped forever.
         */
        // 3 minutes: conservative upper bound for a single BGTask (max OS-granted wall-clock budget).
        // 10 minutes was too long — if the process is killed every 5 min (notification handler),
        // chains piled up in the queue but never executed (stuck behind stale locks).
        const val STALE_LOCK_TIMEOUT_MS = 3 * 60 * 1_000L // 3 minutes
    }

    /**
     * Non-suspend shutdown for use directly in the iOS BGTask `expirationHandler`.
     *
     * iOS expiration handlers must complete **instantly** (synchronously, on the calling thread).
     * A `suspend fun` cannot be called directly from Swift without a completion-callback wrapper
     * — which would compile but is awkward and easy to misuse. Use this method instead:
     *
     * ```swift
     * BGTaskScheduler.shared.register(forTaskWithIdentifier: id) { task in
     *     task.expirationHandler = {
     *         chainExecutor.requestShutdownSync()  // ← non-suspend, direct call
     *     }
     *     Task {
     *         await chainExecutor.executeChainsInBatch()
     *         task.setTaskCompleted(success: true)
     *     }
     * }
     * ```
     *
     * **What it does**:
     * 1. Sets the `isShuttingDown` flag atomically — O(1), never blocks
     * 2. Cancels all active coroutines — O(1) flag-set on coroutine machinery
     * 3. Launches a best-effort progress flush on a background coroutine
     *    (may not finish before the OS terminates the process, but improves resume accuracy)
     *
     * **NOTE**: If you have a coroutine scope available (e.g. you're inside a `Task { }`),
     * call [requestShutdown] instead — it `await`s the flush for maximum progress safety.
     */
    fun requestShutdownSync() {
        if (!isShuttingDown.compareAndSet(0, 1)) {
            Logger.w(LogTags.CHAIN, "Shutdown already in progress")
            return
        }
        Logger.w(LogTags.CHAIN, "🛑 BGTask expired — cancelling active chains (sync)")
        // O(1): sets a cancellation flag on the coroutine job; no I/O, never blocks
        job.cancelChildren()
        // Best-effort: attempt to flush buffered progress before OS terminates the process.
        // This launch returns immediately; the flush may or may not complete in time.
        coroutineScope.launch {
            try {
                fileStorage.flushNow()
                Logger.d(LogTags.CHAIN, "Progress buffer flushed after sync shutdown")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Failed to flush on expiration", e)
            }
        }
    }

    /**
     * Suspend variant of shutdown — awaits the progress flush before returning.
     *
     * Use this when you have a coroutine scope available (e.g. inside `Task { }`) and
     * want to maximise the chance that progress is saved before the OS reclaims budget.
     *
     * **Do NOT call from the BGTask `expirationHandler`** — that callback is synchronous.
     * Use [requestShutdownSync] there.
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
        // Low Power Mode: reduce execution budgets to preserve battery.
        // 85% base → 75%; 70% floor → 50% so the device has more headroom.
        val isLowPower = NSProcessInfo.processInfo().lowPowerModeEnabled
        val baseFactor  = if (isLowPower) 0.75 else 0.85
        val floorFactor = if (isLowPower) 0.50 else 0.70

        val baseBudget = (totalTimeout * baseFactor).toLong()

        // If we have cleanup history, use measured duration + 20% safety buffer
        if (lastCleanupDurationMs > 0L) {
            val safetyBuffer = (lastCleanupDurationMs * 1.2).toLong()
            val adaptiveBudget = totalTimeout - safetyBuffer

            val minBudget = (totalTimeout * floorFactor).toLong()
            val finalBudget = maxOf(minBudget, adaptiveBudget)

            Logger.d(LogTags.CHAIN, """
                Adaptive budget calculation${if (isLowPower) " [Low Power Mode]" else ""}:
                - Total timeout: ${totalTimeout}ms
                - Last cleanup: ${lastCleanupDurationMs}ms
                - Safety buffer: ${safetyBuffer}ms (120%)
                - Base budget (${(baseFactor * 100).toInt()}%): ${baseBudget}ms
                - Adaptive budget: ${adaptiveBudget}ms
                - Floor (${(floorFactor * 100).toInt()}%): ${minBudget}ms
                - Final budget: ${finalBudget}ms
            """.trimIndent())

            return finalBudget
        }

        // No history: Use base budget
        Logger.d(LogTags.CHAIN, "No cleanup history - using base budget (${(baseFactor * 100).toInt()}%): ${baseBudget}ms${if (isLowPower) " [Low Power Mode]" else ""}")
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
        val minTimePerChain = minOf(taskTimeout, conservativeTimeout) // Minimum time needed per chain

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
            fileStorage.clearDeletedMarker(chainId)

            // Distinguish CANCELLED (no new definition) from REPLACED (new definition enqueued).
            // replaceChainAtomic sets the deleted marker AND immediately re-enqueues the chain
            // with a new definition. If we return SKIPPED here the new definition is permanently
            // lost (the chain was already dequeued). We must fall through and execute it instead.
            if (!fileStorage.chainExists(chainId)) {
                Logger.i(LogTags.CHAIN, "Chain $chainId was cancelled (no definition). Skipping execution.")
                KmpWorkManagerRuntime.notifyChainSkipped(
                    TelemetryHook.ChainSkippedEvent(chainId = chainId, platform = "ios", reason = "CANCELLED")
                )
                return NextChainResult.SKIPPED
            }

            // REPLACED — new definition exists, fall through to execute it.
            Logger.i(
                LogTags.CHAIN,
                "Chain $chainId was replaced (REPLACE policy). " +
                    "Cleared stale deleted marker, proceeding to execute new definition."
            )
        }

        Logger.i(LogTags.CHAIN, "Dequeued chain $chainId for execution (Remaining: ${fileStorage.getQueueSize()})")

        // 2. Execute the chain and return the result.
        // Guard against unexpected Throwable (OOM, assertion errors, etc.) to prevent silent
        // queue stalling: if executeChain() throws, the chain is re-enqueued so it can be
        // retried on the next BGTask invocation instead of being silently lost.
        val success = try {
            executeChain(chainId)
        } catch (e: CancellationException) {
            // CancellationException must propagate — it means the BGTask was expired.
            throw e
        } catch (e: Throwable) {
            Logger.e(LogTags.CHAIN, "💥 Unexpected error executing chain $chainId — re-enqueueing to prevent loss", e)
            try {
                fileStorage.enqueueChain(chainId)
                Logger.i(LogTags.CHAIN, "Re-enqueued chain $chainId after unexpected error")
            } catch (re: Exception) {
                Logger.e(LogTags.CHAIN, "Failed to re-enqueue chain $chainId: ${re.message}")
            }
            return NextChainResult.FAILURE
        }
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
            val existingTimestamp = activeChains[chainId]
            if (existingTimestamp != null) {
                val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val ageMs = nowMs - existingTimestamp
                if (ageMs > STALE_LOCK_TIMEOUT_MS) {
                    // Process was likely killed before the finally block could clear the lock.
                    // Evict the stale entry and allow re-execution on this invocation.
                    Logger.w(LogTags.CHAIN, "⚠️ Stale active-chain lock for $chainId (age: ${ageMs}ms > ${STALE_LOCK_TIMEOUT_MS}ms) — evicting and re-running")
                    activeChains[chainId] = nowMs
                    false
                } else {
                    true
                }
            } else {
                activeChains[chainId] = (NSDate().timeIntervalSince1970 * 1000).toLong()
                Logger.d(LogTags.CHAIN, "Marked chain $chainId as active (Total active: ${activeChains.size})")
                false
            }
        }

        if (isAlreadyActive) {
            // Check if this is a REPLACE scenario: a new definition was written by
            // replaceChainAtomic but the OLD coroutine is still running and holds the lock.
            // Cancel the old job so it releases the lock, then proceed with the new definition.
            val oldJob = activeChainJobsMutex.withLock { activeChainJobs[chainId] }
            if (oldJob != null && oldJob.isActive) {
                Logger.i(
                    LogTags.CHAIN,
                    "🔄 REPLACE policy: cancelling in-progress execution of chain $chainId " +
                        "so the replacement can start immediately."
                )
                oldJob.cancelAndJoin()  // Suspend until old finally-block clears activeChains
                // Re-claim the active slot for the replacement execution
                activeChainsMutex.withLock {
                    activeChains[chainId] = (NSDate().timeIntervalSince1970 * 1000).toLong()
                }
            } else {
                Logger.w(LogTags.CHAIN, "⚠️ Chain $chainId is already executing (no active job), skipping duplicate")
                return false
            }
        }

        // Store the current coroutine's Job so a concurrent REPLACE can cancel us if needed.
        // Register in both the per-instance map (within-instance cancellation) and the global
        // ChainJobRegistry (cross-instance cancellation from NativeTaskScheduler.REPLACE).
        currentCoroutineContext()[Job]?.let { currentJob ->
            activeChainJobsMutex.withLock { activeChainJobs[chainId] = currentJob }
            ChainJobRegistry.register(chainId, currentJob)
        }

        // History tracking — accumulated and written in the finally block.
        // historyStatus == null means "don't write a record" (e.g. duplicate / corrupt state).
        var historyStatus: ExecutionStatus? = null
        var historyStartMs: Long = 0L
        var historyTotalSteps: Int = 0
        var historyCompletedSteps: Int = 0
        var historyFailedStep: Int? = null
        var historyError: String? = null
        var historyRetryCount: Int = 0
        var historyWorkerNames: List<String> = emptyList()

        try {
            // 3. Load the chain definition from file storage
            val steps = fileStorage.loadChainDefinition(chainId)
            if (steps == null) {
                Logger.e(LogTags.CHAIN, "No chain definition found for ID: $chainId")
                fileStorage.deleteChainProgress(chainId) // Clean up orphaned progress
                return false
            }
            historyTotalSteps = steps.size
            historyWorkerNames = steps.flatten().map { it.workerClassName }

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
                historyStatus = ExecutionStatus.SKIPPED
                historyStartMs = nowMs
                KmpWorkManagerRuntime.notifyChainSkipped(
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
                    historyStatus = ExecutionStatus.ABANDONED
                    historyStartMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                    historyError = "QUARANTINED — non-idempotent tasks after corrupt progress"
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

            // 5a. Poison-pill guard: increment crash attempt counter and persist BEFORE any work.
            // If the process is killed (OOM, native crash) during execution, this counter is
            // already N+1 on disk. After MAX_CRASH_ATTEMPTS, quarantine the chain so a
            // crash-looping chain does not burn the app's iOS background execution budget.
            progress = progress.withCrashAttempt()
            fileStorage.saveChainProgress(progress)
            if (progress.isPoisonPill()) {
                Logger.e(
                    LogTags.CHAIN,
                    "☠️ Chain $chainId POISON PILL — crashed or was killed ${progress.crashAttemptCount} times " +
                        "(limit: ${ChainProgress.MAX_CRASH_ATTEMPTS}). Quarantining to protect BGTask budget."
                )
                fileStorage.deleteChainDefinition(chainId)
                fileStorage.deleteChainProgress(chainId)
                historyStatus = ExecutionStatus.ABANDONED
                historyStartMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                historyError = "POISON PILL — crashed ${progress.crashAttemptCount} times"
                return false
            }

            // 5. Check if max retries exceeded
            if (progress.hasExceededRetries()) {
                Logger.e(
                    LogTags.CHAIN,
                    "Chain $chainId has exceeded max retries (${progress.retryCount}/${progress.maxRetries}). Abandoning."
                )
                fileStorage.deleteChainDefinition(chainId)
                fileStorage.deleteChainProgress(chainId)
                historyStatus = ExecutionStatus.ABANDONED
                historyStartMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                historyRetryCount = progress.retryCount
                historyError = progress.lastError ?: "Max retries exceeded"
                historyFailedStep = progress.lastFailedStep
                historyCompletedSteps = progress.completedSteps.size
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
            historyStartMs = chainStartMs
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

                            KmpWorkManagerRuntime.notifyChainFailed(
                                TelemetryHook.ChainFailedEvent(
                                    chainId = chainId,
                                    failedStep = index,
                                    platform = "ios",
                                    error = stepError ?: "Unknown failure",
                                    retryCount = progress.retryCount,
                                    willRetry = !progress.hasExceededRetries()
                                )
                            )
                            historyStatus = if (progress.hasExceededRetries()) ExecutionStatus.ABANDONED else ExecutionStatus.FAILURE
                            historyFailedStep = index
                            historyError = stepError
                            historyRetryCount = progress.retryCount
                            historyCompletedSteps = progress.completedSteps.size
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
                val failedStep = progress.getNextStepIndex() ?: (steps.size - 1)
                withContext(NonCancellable) {
                    progress = progress.withTimeout(failedStep, "Chain timed out (${chainTimeout}ms)")
                    fileStorage.saveChainProgress(progress)
                }

                fileStorage.enqueueChain(chainId)
                Logger.i(
                    LogTags.CHAIN,
                    "Chain $chainId timed out after ${chainTimeout}ms — re-queued for resumption " +
                    "(completed ${progress.completedSteps.size}/${steps.size} steps)"
                )
                
                KmpWorkManagerRuntime.notifyChainFailed(
                    TelemetryHook.ChainFailedEvent(
                        chainId = chainId,
                        failedStep = progress.getNextStepIndex() ?: (steps.size - 1),
                        platform = "ios",
                        error = "Chain timed out (${chainTimeout}ms)",
                        retryCount = progress.retryCount,
                        willRetry = true
                    )
                )
                historyStatus = ExecutionStatus.TIMEOUT
                historyFailedStep = progress.getNextStepIndex() ?: (steps.size - 1)
                historyError = "Chain timed out (${chainTimeout}ms)"
                historyRetryCount = progress.retryCount
                historyCompletedSteps = progress.completedSteps.size
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
                withContext(NonCancellable) {
                    fileStorage.enqueueChain(chainId)
                }
                Logger.i(LogTags.CHAIN, "Re-queued chain $chainId for resumption")

                // Schedule next BGTask to resume this chain
                scheduleNextBGTask()

                throw e // Propagate cancellation to properly terminate the outer execution loop
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
            KmpWorkManagerRuntime.notifyChainCompleted(
                TelemetryHook.ChainCompletedEvent(
                    chainId = chainId,
                    totalSteps = steps.size,
                    platform = "ios",
                    durationMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - chainStartMs
                )
            )
            historyStatus = ExecutionStatus.SUCCESS
            historyCompletedSteps = steps.size
            return true

        } finally {
            withContext(NonCancellable) {
                // 9. Always remove from active set (even on failure/timeout) - thread-safe
                // Flush buffered progress before removing from active set
                // Wrap flushNow() in try-catch to guarantee cleanup
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
                // Always remove job reference after execution completes (both per-instance and global).
                activeChainJobsMutex.withLock {
                    val removedJob = activeChainJobs.remove(chainId)
                    if (removedJob != null) ChainJobRegistry.unregister(chainId, removedJob)
                }

                // Persist execution record for history export.
                // CancellationException re-enqueues the chain — no terminal record needed.
                val status = historyStatus
                if (status != null) {
                    val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                    val record = ExecutionRecord(
                        id = NSUUID().UUIDString,
                        chainId = chainId,
                        status = status,
                        startedAtMs = historyStartMs,
                        endedAtMs = nowMs,
                        durationMs = if (historyStartMs > 0L) nowMs - historyStartMs else 0L,
                        totalSteps = historyTotalSteps,
                        completedSteps = historyCompletedSteps,
                        failedStep = historyFailedStep,
                        errorMessage = historyError,
                        retryCount = historyRetryCount,
                        platform = "ios",
                        workerClassNames = historyWorkerNames
                    )
                    try {
                        KmpWorkManagerRuntime.executionHistoryStore?.save(record)
                    } catch (e: Exception) {
                        Logger.w(LogTags.CHAIN, "Failed to save execution history record for $chainId: ${e.message}")
                    }
                }
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
                    // Skip tasks that already succeeded in a previous attempt (prior run, persisted to disk).
                    // Use the immutable `progress` snapshot captured before this coroutineScope block,
                    // NOT the mutable `currentProgress` shared with siblings.  Using `currentProgress`
                    // would be an unsynchronised read-outside-lock; more importantly, a sibling's
                    // in-flight success must NOT cause us to skip this task — we only want to honour
                    // completions from prior persisted runs, which are all captured in `progress`.
                    if (progress.isTaskInStepCompleted(stepIndex, taskIndex)) {
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
        if (task.constraints?.requiresUnmeteredNetwork == true && networkStateProvider.isNetworkCellular()) {
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
            // Disable immediately after reading — leaving batteryMonitoringEnabled=true keeps
            // the hardware sensor active continuously and wastes battery.
            UIDevice.currentDevice().batteryMonitoringEnabled = false
            val isCharging = batteryState != UIDeviceBatteryState.UIDeviceBatteryStateUnplugged &&
                batteryState != UIDeviceBatteryState.UIDeviceBatteryStateUnknown
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

        KmpWorkManagerRuntime.notifyTaskStarted(
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
                    val currentJob = currentCoroutineContext()[Job]
                    val env = WorkerEnvironment(
                        progressListener = object : ProgressListener {
                            override fun onProgressUpdate(progress: WorkerProgress) {
                                coroutineScope.launch {
                                    TaskProgressBus.emit(
                                        TaskProgressEvent(
                                            taskId = chainId ?: task.workerClassName,
                                            taskName = task.workerClassName,
                                            progress = progress
                                        )
                                    )
                                }
                            }
                        },
                        isCancelled = { currentJob?.isCancelled == true || isShuttingDown.value != 0 }
                    )

                    val result = worker.doWork(task.inputJson, env)
                    val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                    val percentage = (duration * 100 / taskTimeout).toInt()

                    if (duration > taskTimeout * 0.8) {
                        Logger.w(LogTags.CHAIN, "⚠️ Task ${task.workerClassName} used ${duration}ms / ${taskTimeout}ms (${percentage}%) - approaching timeout!")
                    }

                    when (result) {
                        is WorkerResult.Success -> {
                            val message = result.message ?: "Task succeeded in ${duration}ms"
                            Logger.d(LogTags.CHAIN, "✅ Task ${task.workerClassName} - $message (${percentage}%)")
                            
                            val completionEvent = TaskCompletionEvent(
                                taskName = task.workerClassName,
                                success = true,
                                message = message,
                                outputData = result.data
                            )
                            
                            // Durable emission
                            TaskEventManager.emit(completionEvent)
                            
                            KmpWorkManagerRuntime.notifyTaskCompleted(
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
                            
                            val completionEvent = TaskCompletionEvent(
                                taskName = task.workerClassName,
                                success = false,
                                message = result.message,
                                outputData = null
                            )
                            
                            // Durable emission
                            TaskEventManager.emit(completionEvent)
                            
                            KmpWorkManagerRuntime.notifyTaskCompleted(
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
                            KmpWorkManagerRuntime.notifyTaskFailed(
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
                KmpWorkManagerRuntime.notifyTaskFailed(
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
                KmpWorkManagerRuntime.notifyTaskFailed(
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
        coroutineScope.launch {
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
        coroutineScope.launch {
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

        val callback = weakContinuationCallback?.get()
        if (callback != null) {
            Logger.d(LogTags.CHAIN, "Invoking continuation callback to schedule next BGTask")
            callback.invoke()
        } else if (!hasContinuationCallback) {
            // No callback was ever provided — developer opted out of continuation scheduling.
            Logger.w(LogTags.CHAIN, "No continuation callback provided — remaining chains will not be scheduled. Pass onContinuationNeeded to ChainExecutor to enable automatic BGTask continuation.")
        } else {
            // Callback was provided but the Swift object that owns it has been deallocated.
            // The WeakReference resolved to null, which means the closure's capture context is gone.
            // Remaining chains are stuck in the queue and will NOT execute until the next app launch
            // or the next BGTask slot — this is a data-availability regression.
            //
            // Root cause: Swift caller used a strong capture in the closure (e.g., `self.scheduleNextBGTask()`)
            // and the owning object was released before all chains in the queue were processed.
            //
            // Fix: ensure the Swift object that owns `onContinuationNeeded` outlives this executor,
            // or use a static/global scheduler reference inside the closure.
            Logger.e(LogTags.CHAIN, "ERROR: continuation callback's Swift owner was deallocated — " +
                "chains remain in queue with no way to schedule the next BGTask. " +
                "Use [weak self] inside onContinuationNeeded and ensure the owning object stays alive " +
                "for the duration of the BGTask session.")
        }

        // Emit event to notify that continuation is needed
        coroutineScope.launch {
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
     * Safely closes the ChainExecutor, ensuring all pending tasks are cancelled
     * and buffered progress is flushed to disk before the scope is destroyed.
     *
     * This is a suspending function to guarantee that iOS background tasks
     * await proper I/O completion instead of prematurely terminating.
     */
    suspend fun close() {
        closeMutex.withLock {
            if (!closedFlag.compareAndSet(0, 1)) {
                Logger.d(LogTags.CHAIN, "ChainExecutor already closed")
                return
            }

            Logger.d(LogTags.CHAIN, "Closing ChainExecutor")

            // cancelAndJoin() cancels all running coroutines AND waits for them to finish
            // their finally blocks before flushing or closing fileStorage.
            job.cancelAndJoin()

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
     * @deprecated Use close() instead
     */
    @Deprecated(
        message = "Use close() instead",
        replaceWith = ReplaceWith("close()"),
        level = DeprecationLevel.WARNING
    )
    suspend fun cleanup() {
        close()
    }
}
