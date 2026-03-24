
package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker

/**
 * Central registry for all built-in workers provided by the library.
 * This ensures that common workers are always discoverable by platform schedulers
 * without requiring manual registration by the user.
 */
object BuiltinWorkerRegistry {
    /**
     * Creates a built-in worker instance based on its class name.
     * Supports both simple name (e.g., "HttpUploadWorker") and Fully Qualified Name.
     *
     * @param workerClassName The name of the worker class.
     * @return A [Worker] instance if it's a known built-in worker, otherwise null.
     */
    fun createWorker(workerClassName: String): Worker? {
        return when (workerClassName) {
            "HttpUploadWorker", HttpUploadWorker::class.qualifiedName -> HttpUploadWorker()
            "HttpDownloadWorker", HttpDownloadWorker::class.qualifiedName -> HttpDownloadWorker()
            "HttpSyncWorker", HttpSyncWorker::class.qualifiedName -> HttpSyncWorker()
            "HttpRequestWorker", HttpRequestWorker::class.qualifiedName -> HttpRequestWorker()
            "FileCompressionWorker", FileCompressionWorker::class.qualifiedName -> FileCompressionWorker()
            else -> null
        }
    }
}
