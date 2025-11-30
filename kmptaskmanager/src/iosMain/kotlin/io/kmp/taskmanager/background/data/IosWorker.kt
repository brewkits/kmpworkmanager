package io.kmp.taskmanager.background.data

/**
 * iOS Worker interface for background task execution.
 *
 * Implement this interface for each type of background work you want to perform on iOS.
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
interface IosWorker {
    /**
     * Performs the background work.
     *
     * **Important**: This method has timeout protection:
     * - Single tasks: 25 seconds (BGAppRefreshTask limit with safety margin)
     * - Heavy tasks: 55 seconds (BGProcessingTask limit with safety margin)
     * - Chain tasks: 20 seconds per task
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return true if work completed successfully, false otherwise
     */
    suspend fun doWork(input: String?): Boolean
}

/**
 * Factory interface for creating iOS workers.
 *
 * Implement this to provide your custom worker implementations.
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
interface IosWorkerFactory {
    /**
     * Creates a worker instance based on the class name.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return Worker instance or null if not found
     */
    fun createWorker(workerClassName: String): IosWorker?
}
