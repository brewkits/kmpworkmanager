package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BGTaskType
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

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
 * **v2.2.1+ Usage with automatic cleanup:**
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
 * Closeable interface for resource cleanup
 */
interface Closeable {
    fun close()
}

class ChainExecutor(
    private val workerFactory: IosWorkerFactory,
    private val taskType: BGTaskType = BGTaskType.PROCESSING
) : Closeable {

    private var isClosed = false
    private val closeMutex = Mutex()

    private val fileStorage = IosFileStorage()
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    // Thread-safe set to track active chains (prevents duplicate execution)
    private val activeChainsMutex = Mutex()
    private val activeChains = mutableSetOf<String>()

    private val shutdownMutex = Mutex()
    // to prevent race conditions
    private var isShuttingDown = false

    /**
     * Measured cleanup duration from previous batch execution
     * Used for adaptive time budget calculation (v2.2.2+)
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
     * - APP_REFRESH: 50 seconds (total ~30s limit, 20s safety margin)
     * - PROCESSING: 300 seconds (5 minutes from typical 5-10 min window)
     */
    private val chainTimeout: Long = when (taskType) {
        BGTaskType.APP_REFRESH -> 50_000L
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

        shutdownMutex.withLock {
            if (isShuttingDown) {
                Logger.w(LogTags.CHAIN, "Shutdown already in progress")
                return
            }

            isShuttingDown = true
            Logger.w(LogTags.CHAIN, "🛑 Graceful shutdown requested - cancelling active chains")
        }

        // Cancel all running chains
        job.cancelChildren()

        // Wait for grace period to allow progress saving
        Logger.i(LogTags.CHAIN, "Waiting ${SHUTDOWN_GRACE_PERIOD_MS}ms for progress to be saved...")
        kotlinx.coroutines.delay(SHUTDOWN_GRACE_PERIOD_MS)

        // v2.2.2+: Flush buffered progress before shutdown
        fileStorage.flushNow()
        Logger.d(LogTags.CHAIN, "Progress buffer flushed during shutdown")

        Logger.i(LogTags.CHAIN, "Graceful shutdown complete. Active chains: ${activeChains.size}")
    }

    /**
     * Thread-safe version using mutex to prevent race conditions
     */
    suspend fun resetShutdownState() {
        checkNotClosed()

        shutdownMutex.withLock {
            isShuttingDown = false
            Logger.d(LogTags.CHAIN, "Shutdown state reset")
        }
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
     * Calculate adaptive time budget based on measured cleanup duration
     *
     * **v2.2.2+ Adaptive Strategy:**
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
     *
     * **Time-slicing strategy (v2.2.2+ Adaptive):**
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

        // FIX: Atomic check-and-reset to prevent TOCTOU race
        shutdownMutex.withLock {
            if (isShuttingDown) {
                Logger.w(LogTags.CHAIN, "Batch execution skipped - shutdown in progress")
                return 0
            }
            // Reset shutdown state inside lock (was outside, causing TOCTOU)
            isShuttingDown = false
            Logger.d(LogTags.CHAIN, "Shutdown state reset (atomic with check)")
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

        var chainsAttempted = 0
        var chainsSucceeded = 0
        var chainsFailed = 0
        var wasKilledBySystem = false

        try {
            withTimeout(conservativeTimeout) {
                repeat(maxChains) {
                    val shouldStop = shutdownMutex.withLock { isShuttingDown }
                    if (shouldStop) {
                        Logger.w(LogTags.CHAIN, "Stopping batch execution - shutdown requested")
                        return@repeat
                    }

                    val elapsedTime = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                    val remainingTime = conservativeTimeout - elapsedTime

                    if (remainingTime < minTimePerChain) {
                        Logger.w(LogTags.CHAIN, "⏱️ Time-slicing: Insufficient time remaining (${remainingTime}ms < ${minTimePerChain}ms), stopping early to preserve iOS credit score")
                        return@repeat
                    }

                    // Execute next chain
                    chainsAttempted++
                    val success = executeNextChainFromQueue()

                    if (success) {
                        chainsSucceeded++

                        // Check if more chains in queue
                        if (getChainQueueSize() == 0) {
                            Logger.i(LogTags.CHAIN, "Queue empty after ${chainsSucceeded} chains")
                            return@repeat
                        }
                    } else {
                        chainsFailed++
                        Logger.w(LogTags.CHAIN, "Chain execution failed, continuing to next")
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

        // Measure cleanup time for adaptive budget (v2.2.2+)
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

        // Record cleanup duration for next run (v2.2.2+)
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
     * @return `true` if the chain was executed successfully or if the queue was empty, `false` otherwise.
     * @throws IllegalStateException if executor is closed
     */
    suspend fun executeNextChainFromQueue(): Boolean {
        checkNotClosed()

        // 1. Retrieve and remove the next chain ID from the queue (atomic operation)
        val chainId = fileStorage.dequeueChain() ?: run {
            Logger.d(LogTags.CHAIN, "Chain queue is empty, nothing to execute")
            return true // Considered success as there's no work to do
        }

        if (fileStorage.isChainDeleted(chainId)) {
            Logger.i(LogTags.CHAIN, "Chain $chainId was deleted (REPLACE policy). Skipping execution...")
            fileStorage.clearDeletedMarker(chainId)
            return true // Success - continue to next chain
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
        return success
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

            // 4. Load or create progress
            var progress = fileStorage.loadChainProgress(chainId) ?: ChainProgress(
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

            // 7. Execute steps sequentially with timeout protection
            try {
                withTimeout(chainTimeout) {
                    for ((index, step) in steps.withIndex()) {
                        // Skip already completed steps
                        if (progress.isStepCompleted(index)) {
                            Logger.d(LogTags.CHAIN, "Skipping already completed step ${index + 1}/${steps.size} for chain $chainId")
                            continue
                        }

                        Logger.i(LogTags.CHAIN, "Executing step ${index + 1}/${steps.size} for chain $chainId (${step.size} tasks)")

                        val (stepSuccess, updatedProgress) = executeStep(step, index, progress)
                        progress = updatedProgress
                        if (!stepSuccess) {
                            Logger.e(LogTags.CHAIN, "Step ${index + 1} failed. Updating progress for chain $chainId")

                            // Update progress with failure and increment retry count
                            // v2.2.2+: Protect progress save from cancellation
                            withContext(NonCancellable) {
                                progress = progress.withFailure(index)
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

                            return@withTimeout false
                        }

                        // Step succeeded - update progress
                        // v2.2.2+: Protect progress save from cancellation
                        withContext(NonCancellable) {
                            progress = progress.withCompletedStep(index)
                            fileStorage.saveChainProgress(progress)
                        }
                        Logger.d(
                            LogTags.CHAIN,
                            "Step ${index + 1}/${steps.size} completed for chain $chainId (${progress.getCompletionPercentage()}% complete)"
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.e(LogTags.CHAIN, "Chain $chainId timed out after ${chainTimeout}ms")

                // Save current progress (don't delete - allow resume)
                // Progress already saved after last successful step
                Logger.i(
                    LogTags.CHAIN,
                    "Chain $chainId progress saved. Will resume from step ${progress.getNextStepIndex()} on next execution."
                )

                return false
            } catch (e: CancellationException) {
                Logger.w(LogTags.CHAIN, "🛑 Chain $chainId cancelled due to graceful shutdown")

                // Progress is already saved after each step, so current progress is persisted
                val nextStep = progress.getNextStepIndex()
                Logger.i(
                    LogTags.CHAIN,
                    "Chain $chainId progress saved (${progress.getCompletionPercentage()}% complete). " +
                    "Will resume from step $nextStep on next BGTask execution."
                )

                // Re-queue the chain for next execution
                fileStorage.enqueueChain(chainId)
                Logger.i(LogTags.CHAIN, "Re-queued chain $chainId for resumption")

                return false
            }

            // 8. Clean up the chain definition and progress upon successful completion
            // v2.2.2+: Flush buffered progress before cleanup
            fileStorage.flushNow()

            fileStorage.deleteChainDefinition(chainId)
            fileStorage.deleteChainProgress(chainId)
            Logger.i(LogTags.CHAIN, "Chain $chainId completed all ${steps.size} steps successfully")
            return true

        } finally {
            // 9. Always remove from active set (even on failure/timeout) - thread-safe
            // v2.2.2+: Flush buffered progress before removing from active set
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
     * Each task's completion is persisted individually so that a crash mid-step
     * still preserves the already-succeeded tasks.
     *
     * @return Pair of (stepSucceeded, updatedProgress)
     */
    private suspend fun executeStep(
        tasks: List<TaskRequest>,
        stepIndex: Int,
        progress: ChainProgress
    ): Pair<Boolean, ChainProgress> {
        if (tasks.isEmpty()) return Pair(true, progress)

        var currentProgress = progress
        // Shared across all async blocks launched below — do not extract into a separate function.
        val progressMutex = Mutex()

        val results = coroutineScope {
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
                        return@async true
                    }

                    val result = executeTask(task)
                    if (result) {
                        // v2.2.2+: Protect progress save from cancellation
                        withContext(NonCancellable) {
                            progressMutex.withLock {
                                currentProgress = currentProgress.withCompletedTaskInStep(stepIndex, taskIndex)
                                fileStorage.saveChainProgress(currentProgress)
                            }
                        }
                    }
                    result
                }
            }.awaitAll()
        }

        val allSucceeded = results.all { it }
        if (!allSucceeded) {
            Logger.w(LogTags.CHAIN, "Step $stepIndex had ${results.count { !it }} failed task(s) out of ${tasks.size}")
        }
        return Pair(allSucceeded, currentProgress)
    }

    /**
     * Execute a single task with timeout protection and detailed logging
     */
    private suspend fun executeTask(task: TaskRequest): Boolean {
        Logger.d(LogTags.CHAIN, "▶️ Starting task: ${task.workerClassName} (timeout: ${taskTimeout}ms)")

        val worker = workerFactory.createWorker(task.workerClassName)
        if (worker == null) {
            Logger.e(LogTags.CHAIN, "❌ Could not create worker for ${task.workerClassName}")
            return false
        }

        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        return try {
            withTimeout(taskTimeout) {
                val result = worker.doWork(task.inputJson)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                val percentage = (duration * 100 / taskTimeout).toInt()

                // Warn if task used > 80% of timeout
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
                        true
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
                        false
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
            Logger.e(LogTags.CHAIN, "⏱️ Task ${task.workerClassName} timed out after ${duration}ms (limit: ${taskTimeout}ms)")

            // Emit failure event with timeout details
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = task.workerClassName,
                    success = false,
                    message = "⏱️ Timeout after ${duration}ms",
                    outputData = null
                )
            )
            false
        } catch (e: Exception) {
            val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
            Logger.e(LogTags.CHAIN, "💥 Task ${task.workerClassName} threw exception after ${duration}ms", e)

            // Emit failure event with exception details
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = task.workerClassName,
                    success = false,
                    message = "💥 Exception: ${e.message}",
                    outputData = null
                )
            )
            false
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

        // TODO: Add analytics/telemetry integration here
        // Analytics.track("batch_execution", metrics)
    }

    /**
     * Schedule next BGTask for continuation
     *
     * Note: This is a placeholder. Actual implementation requires access to
     * NativeTaskScheduler or BGTaskScheduler. In production, this should be
     * called from the BGTask handler after receiving metrics.
     */
    private fun scheduleNextBGTask() {
        Logger.i(LogTags.CHAIN, """
            📅 Continuation needed: Queue has remaining chains

            Implementation note: The BGTask handler should call NativeTaskScheduler
            to schedule the next execution:

            Swift example:
            if executor.getChainQueueSize() > 0 {
                let request = BGProcessingTaskRequest(identifier: "chain_executor")
                request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
                try? BGTaskScheduler.shared.submit(request)
            }
        """.trimIndent())

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
     */
    override fun close() {
        // Use runBlocking to handle suspend function in close()
        kotlinx.coroutines.runBlocking {
            closeMutex.withLock {
                if (isClosed) {
                    Logger.d(LogTags.CHAIN, "ChainExecutor already closed")
                    return@runBlocking
                }

                isClosed = true
                Logger.d(LogTags.CHAIN, "Closing ChainExecutor")

                // v2.2.2+: Flush buffered progress before closing
                fileStorage.flushNow()
                Logger.d(LogTags.CHAIN, "Progress buffer flushed during close")

                // Cancel all running coroutines
                job.cancel()

                Logger.i(LogTags.CHAIN, "ChainExecutor closed successfully")
            }
        }
    }

    /**
     * Check if executor is closed, throw if it is
     */
    private fun checkNotClosed() {
        if (isClosed) {
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
 * Extension function for using Closeable with automatic cleanup
 */
inline fun <T : Closeable, R> T.use(block: (T) -> R): R {
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
