package com.example.kmpworkmanagerv2.background.data

import com.example.kmpworkmanagerv2.background.domain.TaskRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * Executes a task chain on the iOS platform.
 * This class is designed to be called from a background task handler.
 */
class ChainExecutor(private val workerFactory: IosWorkerFactory) {

    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Returns the current number of chains waiting in the execution queue.
     */
    fun getChainQueueSize(): Int {
        return (userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) ?: emptyList<String>()).size
    }

    /**
     * Retrieves the next chain ID from the queue and executes it.
     * @return `true` if the chain was executed successfully or if the queue was empty, `false` otherwise.
     */
    suspend fun executeNextChainFromQueue(): Boolean {
        // 1. Retrieve and remove the next chain ID from the queue.
        val stringArray: List<String>? = userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) as? List<String>
        val queue: MutableList<String> = if (stringArray != null) {
            stringArray.toMutableList()
        } else {
            mutableListOf<String>()
        }
        val chainId = queue.removeFirstOrNull() ?: run {
            println(" KMP_BG_TASK_iOS: Chain execution queue is empty. Nothing to do.")
            return true // Considered success as there's no work to do.
        }
        userDefaults.setObject(queue, forKey = CHAIN_QUEUE_KEY)

        println(" KMP_BG_TASK_iOS: Dequeued chain $chainId for execution. Queue size: ${queue.size}")

        // 2. Execute the chain and return the result.
        val success = executeChain(chainId)
        if (success) {
            println(" KMP_BG_TASK_iOS: Chain $chainId completed successfully.")
        } else {
            println(" KMP_BG_TASK_iOS: Chain $chainId failed.")
        }
        return success
    }

    private suspend fun executeChain(chainId: String): Boolean {
        // 1. Get the chain definition from UserDefaults.
        val chainDefinitionKey = "$CHAIN_DEFINITION_PREFIX$chainId"
        val jsonString = userDefaults.stringForKey(chainDefinitionKey)
        if (jsonString == null) {
            println(" KMP_BG_TASK_iOS: ERROR: No chain definition found for ID $chainId")
            return false
        }

        // 2. Deserialize the chain steps.
        val steps = try {
            Json.decodeFromString<List<List<TaskRequest>>>(jsonString)
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: ERROR: Failed to deserialize chain $chainId. Error: ${e.message}")
            userDefaults.removeObjectForKey(chainDefinitionKey) // Clean up invalid definition
            return false
        }

        // 3. Execute steps sequentially.
        for ((index, step) in steps.withIndex()) {
            println(" KMP_BG_TASK_iOS: Executing step ${index + 1}/${steps.size} for chain $chainId")
            val stepSuccess = executeStep(step)
            if (!stepSuccess) {
                println(" KMP_BG_TASK_iOS: Step ${index + 1} failed. Aborting chain $chainId.")
                userDefaults.removeObjectForKey(chainDefinitionKey) // Clean up on failure
                return false
            }
        }

        // 4. Clean up the chain definition upon successful completion.
        userDefaults.removeObjectForKey(chainDefinitionKey)
        return true
    }

    private suspend fun executeStep(tasks: List<TaskRequest>): Boolean {
        if (tasks.isEmpty()) return true

        // Execute tasks in the step in parallel.
        val results = coroutineScope {
            tasks.map { task ->
                async { executeTask(task) }
            }.awaitAll()
        }

        // The step is successful only if all parallel tasks succeeded.
        return results.all { it }
    }

    private suspend fun executeTask(task: TaskRequest): Boolean {
        val worker = workerFactory.createWorker(task.workerClassName)
        if (worker == null) {
            println(" KMP_BG_TASK_iOS: ERROR: Could not create worker for ${task.workerClassName}")
            return false
        }

        return try {
            worker.doWork(task.inputJson)
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: ERROR: Worker ${task.workerClassName} threw an exception: ${e.message}")
            false
        }
    }

    companion object {
        private const val CHAIN_DEFINITION_PREFIX = "kmp_chain_definition_"
        private const val CHAIN_QUEUE_KEY = "kmp_chain_queue"
    }
}
