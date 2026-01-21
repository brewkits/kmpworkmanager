package dev.brewkits.kmpworkmanager.background.data

/**
 * iOS Worker interface for background task execution.
 *
 * Implement this interface for each type of background work you want to perform on iOS.
 *
 * v4.0.0+: Now extends common Worker interface
 *
 * Example:
 * ```kotlin
 * class SyncWorker : IosWorker {
 *     override suspend fun doWork(input: String?): Boolean {
 *         // Your sync logic here
 *         Logger.i(LogTags.WORKER, "Syncing data...")
 *         delay(2000)
 *         return true
 *     }
 * }
 * ```
 */
interface IosWorker : dev.brewkits.kmpworkmanager.background.domain.Worker {
    /**
     * Performs the background work.
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
     * @return true if work completed successfully, false otherwise
     */
    override suspend fun doWork(input: String?): Boolean
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
