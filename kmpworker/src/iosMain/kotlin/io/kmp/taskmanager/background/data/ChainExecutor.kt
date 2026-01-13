package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.background.domain.TaskRequest
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlinx.coroutines.*
import platform.Foundation.NSDate
import platform.Foundation.NSMutableSet
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
 */
class ChainExecutor(private val workerFactory: IosWorkerFactory) {

    private val fileStorage = IosFileStorage()
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    // Thread-safe set to track active chains (prevents duplicate execution)
    private val activeChains = NSMutableSet()

    companion object {
        /**
         * Timeout for individual tasks within chain (20 seconds)
         * Allows multiple tasks to execute within BGTask time limit
         */
        const val TASK_TIMEOUT_MS = 20_000L

        /**
         * Maximum time for chain execution (50 seconds)
         * Provides 10s safety margin for BGProcessingTask (60s limit)
         */
        const val CHAIN_TIMEOUT_MS = 50_000L
    }

    /**
     * Returns the current number of chains waiting in the execution queue.
     */
    fun getChainQueueSize(): Int {
        return fileStorage.getQueueSize()
    }

    /**
     * Execute multiple chains from the queue in batch mode.
     * This optimizes iOS BGTask usage by processing as many chains as possible
     * before the OS time limit is reached.
     *
     * @param maxChains Maximum number of chains to process (default: 3)
     * @param totalTimeoutMs Total timeout for batch processing (default: 50s)
     * @return Number of successfully executed chains
     */
    suspend fun executeChainsInBatch(
        maxChains: Int = 3,
        totalTimeoutMs: Long = CHAIN_TIMEOUT_MS
    ): Int {
        Logger.i(LogTags.CHAIN, "Starting batch chain execution (max: $maxChains, timeout: ${totalTimeoutMs}ms)")

        var executedCount = 0
        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        try {
            withTimeout(totalTimeoutMs) {
                repeat(maxChains) {
                    // Check remaining time
                    val elapsedTime = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                    val remainingTime = totalTimeoutMs - elapsedTime

                    if (remainingTime < 10_000L) {
                        Logger.w(LogTags.CHAIN, "Insufficient time remaining (${remainingTime}ms), stopping batch")
                        return@repeat
                    }

                    // Execute next chain
                    val success = executeNextChainFromQueue()
                    if (success && getChainQueueSize() > 0) {
                        executedCount++
                    } else {
                        // Queue empty or chain failed
                        return@repeat
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.CHAIN, "Batch execution timed out after ${totalTimeoutMs}ms")
        }

        Logger.i(LogTags.CHAIN, "Batch execution completed: $executedCount chains executed")
        return executedCount
    }

    /**
     * Retrieves the next chain ID from the queue and executes it.
     * @return `true` if the chain was executed successfully or if the queue was empty, `false` otherwise.
     */
    suspend fun executeNextChainFromQueue(): Boolean {
        // 1. Retrieve and remove the next chain ID from the queue (atomic operation)
        val chainId = fileStorage.dequeueChain() ?: run {
            Logger.d(LogTags.CHAIN, "Chain queue is empty, nothing to execute")
            return true // Considered success as there's no work to do
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
     * Execute a single chain by ID
     */
    private suspend fun executeChain(chainId: String): Boolean {
        // 1. Check for duplicate execution (race condition protection)
        if (activeChains.member(chainId) != null) {
            Logger.w(LogTags.CHAIN, "⚠️ Chain $chainId is already executing, skipping duplicate")
            return false
        }

        // 2. Mark chain as active
        activeChains.addObject(chainId)
        Logger.d(LogTags.CHAIN, "Marked chain $chainId as active (Total active: ${activeChains.count})")

        try {
            // 3. Load the chain definition from file storage
            val steps = fileStorage.loadChainDefinition(chainId)
            if (steps == null) {
                Logger.e(LogTags.CHAIN, "No chain definition found for ID: $chainId")
                return false
            }

            Logger.d(LogTags.CHAIN, "Executing chain $chainId with ${steps.size} steps")

            // 4. Execute steps sequentially with timeout protection
            try {
                withTimeout(CHAIN_TIMEOUT_MS) {
                    for ((index, step) in steps.withIndex()) {
                        Logger.i(LogTags.CHAIN, "Executing step ${index + 1}/${steps.size} for chain $chainId (${step.size} tasks)")

                        val stepSuccess = executeStep(step)
                        if (!stepSuccess) {
                            Logger.e(LogTags.CHAIN, "Step ${index + 1} failed. Aborting chain $chainId")
                            fileStorage.deleteChainDefinition(chainId) // Clean up on failure
                            return@withTimeout false
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.e(LogTags.CHAIN, "Chain $chainId timed out after ${CHAIN_TIMEOUT_MS}ms")
                fileStorage.deleteChainDefinition(chainId)
                return false
            }

            // 5. Clean up the chain definition upon successful completion
            fileStorage.deleteChainDefinition(chainId)
            Logger.i(LogTags.CHAIN, "Chain $chainId completed all steps successfully")
            return true

        } finally {
            // 6. Always remove from active set (even on failure/timeout)
            activeChains.removeObject(chainId)
            Logger.d(LogTags.CHAIN, "Removed chain $chainId from active set (Remaining active: ${activeChains.count})")
        }
    }

    /**
     * Execute all tasks in a step (parallel execution)
     */
    private suspend fun executeStep(tasks: List<TaskRequest>): Boolean {
        if (tasks.isEmpty()) return true

        // Execute tasks in the step in parallel with individual timeouts
        val results = coroutineScope {
            tasks.map { task ->
                async {
                    executeTask(task)
                }
            }.awaitAll()
        }

        // The step is successful only if all parallel tasks succeeded
        val allSucceeded = results.all { it }
        if (!allSucceeded) {
            Logger.w(LogTags.CHAIN, "Step had ${results.count { !it }} failed task(s) out of ${tasks.size}")
        }
        return allSucceeded
    }

    /**
     * Execute a single task with timeout protection and detailed logging
     */
    private suspend fun executeTask(task: TaskRequest): Boolean {
        Logger.d(LogTags.CHAIN, "▶️ Starting task: ${task.workerClassName}")

        val worker = workerFactory.createWorker(task.workerClassName)
        if (worker == null) {
            Logger.e(LogTags.CHAIN, "❌ Could not create worker for ${task.workerClassName}")
            return false
        }

        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        return try {
            withTimeout(TASK_TIMEOUT_MS) {
                val result = worker.doWork(task.inputJson)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
                val percentage = (duration * 100 / TASK_TIMEOUT_MS).toInt()

                // Warn if task used > 80% of timeout
                if (duration > TASK_TIMEOUT_MS * 0.8) {
                    Logger.w(LogTags.CHAIN, "⚠️ Task ${task.workerClassName} used ${duration}ms / ${TASK_TIMEOUT_MS}ms (${percentage}%) - approaching timeout!")
                }

                if (result) {
                    Logger.d(LogTags.CHAIN, "✅ Task ${task.workerClassName} succeeded in ${duration}ms (${percentage}%)")
                } else {
                    Logger.w(LogTags.CHAIN, "❌ Task ${task.workerClassName} failed after ${duration}ms")
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
            Logger.e(LogTags.CHAIN, "⏱️ Task ${task.workerClassName} timed out after ${duration}ms (limit: ${TASK_TIMEOUT_MS}ms)")

            // Emit failure event with timeout details
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = task.workerClassName,
                    success = false,
                    message = "⏱️ Timeout after ${duration}ms"
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
                    message = "💥 Exception: ${e.message}"
                )
            )
            false
        }
    }

    /**
     * Emit chain failure event to UI
     */
    private fun emitChainFailureEvent(chainId: String) {
        CoroutineScope(Dispatchers.Main).launch {
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
     * Cleanup coroutine scope (call when executor is no longer needed)
     */
    fun cleanup() {
        Logger.d(LogTags.CHAIN, "Cleaning up ChainExecutor")
        job.cancel()
    }
}
