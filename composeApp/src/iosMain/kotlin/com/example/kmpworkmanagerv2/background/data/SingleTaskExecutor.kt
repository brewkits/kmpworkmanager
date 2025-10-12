package com.example.kmpworkmanagerv2.background.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Executes a single, non-chained background task on the iOS platform.
 */
class SingleTaskExecutor(private val workerFactory: IosWorkerFactory) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Creates and runs a worker based on its class name.
     * @param workerClassName The fully qualified name of the worker class.
     * @param input Optional input data for the worker.
     * @return `true` if the work succeeded, `false` otherwise.
     */
    suspend fun executeTask(workerClassName: String, input: String?): Boolean {
        val worker = workerFactory.createWorker(workerClassName)
        if (worker == null) {
            println(" KMP_BG_TASK_iOS: ERROR: Could not create worker for $workerClassName")
            return false
        }

        return try {
            // We use an async block to ensure we can catch exceptions from the suspend function
            coroutineScope.async { worker.doWork(input) }.await()
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: ERROR: Worker $workerClassName threw an exception: ${e.message}")
            false
        }
    }
}
