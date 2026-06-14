package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker

/**
 * Registry for the Ktor-based built-in HTTP workers shipped in the `kmpworkmanager-http`
 * artifact. Split out of the core `kmpworker` module so the core engine carries no Ktor
 * dependency (see [BuiltinWorkerRegistry] for the non-HTTP built-ins that remain in core).
 *
 * **HTTP Workers:**
 * - `HttpRequestWorker`: Generic HTTP requests (GET, POST, PUT, DELETE, PATCH)
 * - `HttpSyncWorker`: JSON synchronization (POST/GET JSON data)
 * - `HttpDownloadWorker`: Download files from HTTP/HTTPS URLs
 * - `HttpUploadWorker`: Upload files using multipart/form-data
 *
 * **Usage (compose with core built-ins and/or your own factory):**
 * ```kotlin
 * KmpWorkManager.initialize(
 *     context = this,
 *     workerFactory = CompositeWorkerFactory(
 *         MyWorkerFactory(),       // your custom workers
 *         HttpWorkerRegistry,      // Ktor HTTP workers (this artifact)
 *         BuiltinWorkerRegistry,   // non-HTTP built-ins (core)
 *     )
 * )
 * ```
 *
 * Accepts both simple names ("HttpRequestWorker") and fully qualified names
 * ("dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker").
 */
object HttpWorkerRegistry : WorkerFactory {

    override fun createWorker(workerClassName: String): Worker? {
        return when (workerClassName.substringAfterLast('.')) {
            "HttpRequestWorker" -> HttpRequestWorker()
            "HttpSyncWorker" -> HttpSyncWorker()
            "HttpDownloadWorker" -> HttpDownloadWorker()
            "HttpUploadWorker" -> HttpUploadWorker()
            else -> null
        }
    }

    /** Fully qualified class names of all HTTP built-in workers in this artifact. */
    fun listWorkers(): List<String> = listOf(
        "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker",
        "dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker",
        "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker",
        "dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker",
    )
}
