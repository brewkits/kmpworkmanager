package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.workers.builtins.*

/**
 * Registry for built-in workers provided by KMP WorkManager.
 *
 * This factory can be used standalone or composed with your custom worker factory.
 *
 * **Built-in Workers:**
 * - `HttpRequestWorker`: Generic HTTP requests (GET, POST, PUT, DELETE, PATCH)
 * - `HttpSyncWorker`: JSON synchronization (POST/GET JSON data)
 * - `HttpDownloadWorker`: Download files from HTTP/HTTPS URLs
 * - `HttpUploadWorker`: Upload files using multipart/form-data
 * - `FileCompressionWorker`: Compress files/directories into ZIP archives
 *
 * **Usage (Standalone):**
 * ```kotlin
 * KmpWorkManager.initialize(
 *     context = this,
 *     workerFactory = BuiltinWorkerRegistry
 * )
 * ```
 *
 * **Usage (Composed with Custom Workers):**
 * ```kotlin
 * class MyWorkerFactory : WorkerFactory {
 *     override fun createWorker(workerClassName: String): Worker? {
 *         return when(workerClassName) {
 *             "MyCustomWorker" -> MyCustomWorker()
 *             else -> null
 *         }
 *     }
 * }
 *
 * // Compose custom factory with built-in workers
 * KmpWorkManager.initialize(
 *     context = this,
 *     workerFactory = CompositeWorkerFactory(
 *         MyWorkerFactory(),
 *         BuiltinWorkerRegistry
 *     )
 * )
 * ```
 *
 * **Supported Worker Class Names:**
 * - "HttpRequestWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker"
 * - "HttpSyncWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker"
 * - "HttpDownloadWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker"
 * - "HttpUploadWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker"
 * - "FileCompressionWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
 */
object BuiltinWorkerRegistry : WorkerFactory {

    /**
     * Creates a built-in worker instance based on the class name.
     *
     * Supports both simple class names (e.g., "HttpRequestWorker") and
     * fully qualified names (e.g., "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker").
     *
     * @param workerClassName The class name of the worker
     * @return Worker instance or null if not a built-in worker
     */
    override fun createWorker(workerClassName: String): Worker? {
        // Normalize class name (support both simple and fully qualified names)
        val simpleName = workerClassName.substringAfterLast('.')

        return when (simpleName) {
            "HttpRequestWorker" -> HttpRequestWorker()
            "HttpSyncWorker" -> HttpSyncWorker()
            "HttpDownloadWorker" -> HttpDownloadWorker()
            "HttpUploadWorker" -> HttpUploadWorker()
            "FileCompressionWorker" -> FileCompressionWorker()
            else -> null
        }
    }

    /**
     * Returns a list of all built-in worker class names.
     *
     * @return List of fully qualified class names for all built-in workers
     */
    fun listWorkers(): List<String> {
        return listOf(
            "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker",
            "dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker",
            "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker",
            "dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker",
            "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
        )
    }
}

/**
 * Composite worker factory that tries multiple factories in order.
 *
 * This allows you to combine your custom workers with built-in workers.
 * The first factory to return a non-null worker wins.
 *
 * **Usage:**
 * ```kotlin
 * class MyWorkerFactory : WorkerFactory {
 *     override fun createWorker(workerClassName: String): Worker? {
 *         return when(workerClassName) {
 *             "MyWorker" -> MyWorker()
 *             else -> null
 *         }
 *     }
 * }
 *
 * val compositeFactory = CompositeWorkerFactory(
 *     MyWorkerFactory(),      // Try custom workers first
 *     BuiltinWorkerRegistry   // Fall back to built-in workers
 * )
 * ```
 *
 * @property factories List of worker factories to try in order
 */
class CompositeWorkerFactory(
    private vararg val factories: WorkerFactory
) : WorkerFactory {

    override fun createWorker(workerClassName: String): Worker? {
        for (factory in factories) {
            val worker = factory.createWorker(workerClassName)
            if (worker != null) {
                return worker
            }
        }
        return null
    }
}
