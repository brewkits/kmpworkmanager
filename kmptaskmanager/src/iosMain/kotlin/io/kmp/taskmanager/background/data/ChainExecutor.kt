package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.background.domain.TaskRequest
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/**
 * Executes task chains on the iOS platform with batch processing support.
 *
 * Features:
 * - Batch processing: Execute multiple chains in one BGTask invocation
 * - Timeout protection per task
 * - Comprehensive error handling and logging
 * - Task completion event emission
 * - Memory-safe coroutine scope management
 */
class ChainExecutor(private val workerFactory: IosWorkerFactory) {

    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

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

        /**
         * UserDefaults keys for chain persistence
         */
        private const val CHAIN_DEFINITION_PREFIX = "kmp_chain_definition_"
        private const val CHAIN_QUEUE_KEY = "kmp_chain_queue"
    }

    /**
     * Returns the current number of chains waiting in the execution queue.
     */
    fun getChainQueueSize(): Int {
        val queue = userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) as? List<String>
        return queue?.size ?: 0
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
        // 1. Retrieve and remove the next chain ID from the queue
        val stringArray: List<String>? = userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) as? List<String>
        val queue: MutableList<String> = stringArray?.toMutableList() ?: mutableListOf()

        val chainId = queue.removeFirstOrNull() ?: run {
            Logger.d(LogTags.CHAIN, "Chain queue is empty, nothing to execute")
            return true // Considered success as there's no work to do
        }
        userDefaults.setObject(queue, forKey = CHAIN_QUEUE_KEY)

        Logger.i(LogTags.CHAIN, "Dequeued chain $chainId for execution (Queue size: ${queue.size})")

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
        // 1. Get the chain definition from UserDefaults
        val chainDefinitionKey = "$CHAIN_DEFINITION_PREFIX$chainId"
        val jsonString = userDefaults.stringForKey(chainDefinitionKey)
        if (jsonString == null) {
            Logger.e(LogTags.CHAIN, "No chain definition found for ID: $chainId")
            return false
        }

        // 2. Deserialize the chain steps
        val steps = try {
            Json.decodeFromString<List<List<TaskRequest>>>(jsonString)
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Failed to deserialize chain $chainId", e)
            userDefaults.removeObjectForKey(chainDefinitionKey) // Clean up invalid definition
            return false
        }

        Logger.d(LogTags.CHAIN, "Executing chain $chainId with ${steps.size} steps")

        // 3. Execute steps sequentially with timeout protection
        try {
            withTimeout(CHAIN_TIMEOUT_MS) {
                for ((index, step) in steps.withIndex()) {
                    Logger.i(LogTags.CHAIN, "Executing step ${index + 1}/${steps.size} for chain $chainId (${step.size} tasks)")

                    val stepSuccess = executeStep(step)
                    if (!stepSuccess) {
                        Logger.e(LogTags.CHAIN, "Step ${index + 1} failed. Aborting chain $chainId")
                        userDefaults.removeObjectForKey(chainDefinitionKey) // Clean up on failure
                        return@withTimeout false
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.CHAIN, "Chain $chainId timed out after ${CHAIN_TIMEOUT_MS}ms")
            userDefaults.removeObjectForKey(chainDefinitionKey)
            return false
        }

        // 4. Clean up the chain definition upon successful completion
        userDefaults.removeObjectForKey(chainDefinitionKey)
        Logger.i(LogTags.CHAIN, "Chain $chainId completed all steps successfully")
        return true
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
     * Execute a single task with timeout protection
     */
    private suspend fun executeTask(task: TaskRequest): Boolean {
        Logger.d(LogTags.CHAIN, "Executing task: ${task.workerClassName}")

        val worker = workerFactory.createWorker(task.workerClassName)
        if (worker == null) {
            Logger.e(LogTags.CHAIN, "Could not create worker for ${task.workerClassName}")
            return false
        }

        return try {
            withTimeout(TASK_TIMEOUT_MS) {
                val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val result = worker.doWork(task.inputJson)
                val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime

                if (result) {
                    Logger.d(LogTags.CHAIN, "Task ${task.workerClassName} completed (${duration}ms)")
                } else {
                    Logger.w(LogTags.CHAIN, "Task ${task.workerClassName} returned failure (${duration}ms)")
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(LogTags.CHAIN, "Task ${task.workerClassName} timed out after ${TASK_TIMEOUT_MS}ms")
            false
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Task ${task.workerClassName} threw exception", e)
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
