package io.brewkits.kmpworkmanager.background.domain

/**
 * Android worker factory interface.
 *
 * Implement this to provide your Android worker implementations.
 *
 * Example:
 * ```kotlin
 * class MyWorkerFactory : AndroidWorkerFactory {
 *     override fun createWorker(workerClassName: String): AndroidWorker? {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> null
 *         }
 *     }
 * }
 * ```
 *
 * v1.0.0+: Required for registering custom workers with KMP WorkManager
 */
interface AndroidWorkerFactory : io.brewkits.kmpworkmanager.background.domain.WorkerFactory {
    /**
     * Creates an Android worker instance based on the class name.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return AndroidWorker instance or null if not found
     */
    override fun createWorker(workerClassName: String): AndroidWorker?
}
