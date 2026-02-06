package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/**
 * iOS Worker interface for background task execution.
 *
 * Implement this interface for each type of background work you want to perform on iOS.
 *
 * v2.3.0+: Changed return type from Boolean to WorkerResult
 * v4.0.0+: Now extends common Worker interface
 *
 * Example:
 * ```kotlin
 * class SyncWorker : IosWorker {
 *     override suspend fun doWork(input: String?): WorkerResult {
 *         return try {
 *             // Your sync logic here
 *             Logger.i(LogTags.WORKER, "Syncing data...")
 *             delay(2000)
 *             WorkerResult.Success(
 *                 message = "Sync completed",
 *                 data = mapOf("syncedCount" to 10)
 *             )
 *         } catch (e: Exception) {
 *             WorkerResult.Failure("Sync failed: ${e.message}")
 *         }
 *     }
 * }
 * ```
 */
interface IosWorker : dev.brewkits.kmpworkmanager.background.domain.Worker {
    /**
     * Performs the background work.
     *
     * v2.3.0+: Return type changed from Boolean to WorkerResult
     *
     * **Important**: This method has timeout protection:
     * - Chain tasks: 20 seconds per task (ChainExecutor.TASK_TIMEOUT_MS)
     * - Total chain execution: 50 seconds (ChainExecutor.CHAIN_TIMEOUT_MS)
     *
     * **Note**: iOS BGTask limits:
     * - BGAppRefreshTask: ~30 seconds (actual limit, system-controlled)
     * - BGProcessingTask: ~60 seconds (actual limit, system-controlled)
     * - Both are subject to iOS expiration handler which may fire earlier
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return WorkerResult indicating success/failure with optional data and message
     */
    override suspend fun doWork(input: String?): WorkerResult
}

/**
 * Factory interface for creating iOS workers.
 *
 * Implement this to provide your custom worker implementations.
 *
 * v4.0.0+: Now extends common WorkerFactory interface
 *
 * Example:
 * ```kotlin
 * class MyWorkerFactory : IosWorkerFactory {
 *     override fun createWorker(workerClassName: String): IosWorker? {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> null
 *         }
 *     }
 * }
 * ```
 */
interface IosWorkerFactory : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
    /**
     * Creates a worker instance based on the class name.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return Worker instance or null if not found
     */
    override fun createWorker(workerClassName: String): IosWorker?
}
