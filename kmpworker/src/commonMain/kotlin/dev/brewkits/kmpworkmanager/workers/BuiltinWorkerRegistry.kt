package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker

/**
 * Registry for the core (non-HTTP) built-in workers provided by KMP WorkManager.
 *
 * This factory can be used standalone or composed with your custom worker factory.
 *
 * The Ktor-based HTTP workers live in the separate `kmpworkmanager-http` artifact —
 * see `HttpWorkerRegistry` there — so the core engine carries no Ktor dependency.
 *
 * **Built-in Workers (core):**
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
 *     override fun createWorker(workerClassName: String): Worker {
 *         return when(workerClassName) {
 *             "MyCustomWorker" -> MyCustomWorker()
 *             else -> throw IllegalArgumentException("Unregistered worker: $workerClassName")
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
 * **Supported Worker Class Names (this registry only):**
 * - "FileCompressionWorker" or "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
 *
 * **HTTP workers are NOT included here.** "HttpRequestWorker", "HttpSyncWorker",
 * "HttpDownloadWorker", "HttpUploadWorker", and the parallel variants live in the separate
 * `kmpworker-http` Gradle module/artifact and its own `HttpWorkerRegistry` (see the class
 * doc above) — depend on that module and use `CompositeWorkerFactory(HttpWorkerRegistry,
 * BuiltinWorkerRegistry, ...)` (or your own factory) to get both.
 */
object BuiltinWorkerRegistry : WorkerFactory {

    /**
     * Creates a built-in worker instance based on the class name.
     *
     * Supports both simple class names (e.g., "HttpRequestWorker") and
     * fully qualified names (e.g., "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker").
     *
     * @param workerClassName The class name of the worker
     * @return Worker instance for a recognised built-in class name, or `null` if not a built-in worker
     */
    override fun createWorker(workerClassName: String): Worker? {
        // Normalize class name (support both simple and fully qualified names)
        val simpleName = workerClassName.substringAfterLast('.')

        return when (simpleName) {
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
            "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
        )
    }
}

/**
 * Composite worker factory that tries multiple factories in order.
 *
 * **Preferred contract** (matches [WorkerFactory.createWorker] itself, and what
 * [BuiltinWorkerRegistry] and [DelegatingWorkerFactory] both use): return `null` for an
 * unrecognised worker name so this class can try the next factory.
 *
 * **Legacy contract, also supported:** a factory may instead throw [IllegalArgumentException]
 * for an unrecognised name — this class catches exactly that exception type and moves on.
 * Prefer returning `null`; only rely on the throwing form if you cannot change the factory.
 * A factory that throws any other exception type is NOT caught here and will propagate.
 *
 * If no factory recognises the name (every one returned `null` or threw
 * [IllegalArgumentException]), this class itself throws [IllegalArgumentException] — this is
 * a deliberate fail-fast choice for a top-level composite, unlike the individual factories it
 * wraps, which are expected to return `null`.
 *
 * **Usage (preferred, null-based):**
 * ```kotlin
 * class MyWorkerFactory : WorkerFactory {
 *     override fun createWorker(workerClassName: String): Worker? {
 *         return when (workerClassName) {
 *             "MyWorker" -> MyWorker()
 *             else -> null  // Not handled — let CompositeWorkerFactory try the next one
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
            try {
                val worker = factory.createWorker(workerClassName)
                if (worker != null) return worker
            } catch (_: IllegalArgumentException) {
                // This factory doesn't know this worker — try the next one
            }
        }
        throw IllegalArgumentException(
            "'$workerClassName' was not recognised by any of the ${factories.size} registered factories."
        )
    }
}
