package io.kmp.taskmanager.background.domain

/**
 * Platform-agnostic worker factory interface.
 *
 * Users implement this interface to provide their custom worker implementations.
 * The library uses this factory to instantiate workers at runtime based on class names.
 *
 * Example (Common code):
 * ```kotlin
 * class MyWorkerFactory : WorkerFactory {
 *     override fun createWorker(workerClassName: String): Worker? {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> null
 *         }
 *     }
 * }
 * ```
 *
 * v4.0.0+: Replaces hardcoded worker registrations
 */
interface WorkerFactory {
    /**
     * Creates a worker instance based on the class name.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return Worker instance or null if not found
     */
    fun createWorker(workerClassName: String): Worker?
}

/**
 * Platform-agnostic worker interface.
 *
 * Implement this interface for each type of background work.
 * The actual platform implementation will wrap this:
 * - Android: Called from KmpWorker/KmpHeavyWorker/AlarmReceiver
 * - iOS: Implements IosWorker directly
 *
 * Example:
 * ```kotlin
 * class SyncWorker : Worker {
 *     override suspend fun doWork(input: String?): Boolean {
 *         // Your sync logic here
 *         delay(2000)
 *         return true
 *     }
 * }
 * ```
 */
interface Worker {
    /**
     * Performs the background work.
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return true if work completed successfully, false otherwise
     */
    suspend fun doWork(input: String?): Boolean
}
