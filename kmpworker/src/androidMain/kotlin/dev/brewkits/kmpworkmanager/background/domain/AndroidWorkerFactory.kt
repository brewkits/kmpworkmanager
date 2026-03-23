package dev.brewkits.kmpworkmanager.background.domain

/**
 * Android worker factory interface.
 *
 * Implement this to provide your Android worker implementations.
 * Throw [IllegalArgumentException] for unrecognised class names (Fail Fast).
 *
 * Example:
 * ```kotlin
 * class MyWorkerFactory : AndroidWorkerFactory {
 *     override fun createWorker(workerClassName: String): AndroidWorker {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> throw IllegalArgumentException("Unregistered worker: $workerClassName")
 *         }
 *     }
 * }
 * ```
 *
 * v1.0.0+: Required for registering custom workers with KMP WorkManager
 */
interface AndroidWorkerFactory : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
    /**
     * Creates an Android worker instance based on the class name.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return AndroidWorker instance — never null
     * @throws IllegalArgumentException if [workerClassName] is not registered
     */
    override fun createWorker(workerClassName: String): AndroidWorker
}
