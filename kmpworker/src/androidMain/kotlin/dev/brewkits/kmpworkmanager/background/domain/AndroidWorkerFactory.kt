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
 * Required for registering custom workers with KMP WorkManager
 */
interface AndroidWorkerFactory : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
    /**
     * Creates an Android worker instance based on the class name.
     *
     * Return `null` to signal that this factory does not handle [workerClassName],
     * allowing the caller to fall through to another factory or fail gracefully.
     * Throw [IllegalArgumentException] only when the class name is known but invalid.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return AndroidWorker instance, or null if this factory does not handle the class name
     */
    override fun createWorker(workerClassName: String): AndroidWorker?
}
