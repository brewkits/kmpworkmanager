package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import platform.BackgroundTasks.BGTask

/**
 * Registry for iOS task handlers (ChainExecutor and SingleTaskExecutor).
 *
 * This provides a DI-agnostic way to access task executors on iOS, using lazy initialization
 * with the WorkerFactory from WorkerManagerConfig.
 *
 * Usage (in Swift AppDelegate):
 * ```swift
 * func handleBackgroundTask(_ task: BGTask) {
 *     // Kotlin code will access executors via IosTaskHandlerRegistry
 *     IosTaskHandlerRegistryKt.handleTask(task: task, taskId: task.identifier)
 * }
 * ```
 *
 * Note: This replaces Koin-based injection for ChainExecutor and SingleTaskExecutor.
 *
 * @since 2.1.0
 */
object IosTaskHandlerRegistry {
    /**
     * Lazy-initialized ChainExecutor.
     *
     * Created on first access using the WorkerFactory from WorkerManagerConfig.
     * Thread-safe via lazy delegation.
     */
    private val chainExecutor: ChainExecutor by lazy {
        val factory = IosWorkerFactoryProvider.getIosWorkerFactory()
        Logger.i(LogTags.SCHEDULER, "IosTaskHandlerRegistry: Creating ChainExecutor")
        ChainExecutor(workerFactory = factory)
    }

    /**
     * Lazy-initialized SingleTaskExecutor.
     *
     * Created on first access using the WorkerFactory from WorkerManagerConfig.
     * Thread-safe via lazy delegation.
     */
    private val singleTaskExecutor: SingleTaskExecutor by lazy {
        val factory = IosWorkerFactoryProvider.getIosWorkerFactory()
        Logger.i(LogTags.SCHEDULER, "IosTaskHandlerRegistry: Creating SingleTaskExecutor")
        SingleTaskExecutor(workerFactory = factory)
    }

    /**
     * Retrieves the ChainExecutor instance.
     *
     * @return ChainExecutor for handling task chains
     * @throws IllegalStateException if WorkerManagerConfig is not initialized
     */
    fun getChainExecutor(): ChainExecutor = chainExecutor

    /**
     * Retrieves the SingleTaskExecutor instance.
     *
     * @return SingleTaskExecutor for handling single tasks
     * @throws IllegalStateException if WorkerManagerConfig is not initialized
     */
    fun getSingleTaskExecutor(): SingleTaskExecutor = singleTaskExecutor

    /**
     * Handles an iOS BGTask using the appropriate executor.
     *
     * This is a convenience method that can be called directly from Swift/Objective-C
     * to handle background tasks.
     *
     * @param task The BGTask to handle
     * @param taskId The task identifier
     */
    suspend fun handleTask(task: BGTask, taskId: String) {
        Logger.i(LogTags.SCHEDULER, "IosTaskHandlerRegistry: Handling task $taskId")

        when {
            taskId.contains("chain", ignoreCase = true) -> {
                Logger.d(LogTags.SCHEDULER, "Task $taskId identified as chain task")
                val executor = getChainExecutor()
                executor.executeNextChainFromQueue()
            }
            else -> {
                Logger.d(LogTags.SCHEDULER, "Task $taskId identified as single task")
                val executor = getSingleTaskExecutor()
                // Single task execution logic would go here
                // This is just a skeleton - actual implementation depends on task metadata
                Logger.w(LogTags.SCHEDULER, "Single task execution not implemented in handleTask()")
            }
        }
    }

    /**
     * Resets the registry, clearing all cached executors.
     *
     * **Warning**: This is intended for testing only. Do not call in production code.
     * Calling this while tasks are running may cause undefined behavior.
     */
    @Suppress("unused") // Used in tests
    fun reset() {
        // Note: Cannot actually reset lazy delegates without reflection
        // This method is a placeholder for future implementation
        Logger.w(LogTags.SCHEDULER, "IosTaskHandlerRegistry: reset() called but lazy delegates cannot be reset")
    }
}
