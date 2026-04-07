package dev.brewkits.kmpworkmanager.background.domain

/**
 * Platform-agnostic worker factory interface.
 *
 * Users implement this interface to provide their custom worker implementations.
 * The library uses this factory to instantiate workers at runtime based on class names.
 *
 * **Fail Fast**: throw [IllegalArgumentException] for unrecognised class names.
 * The library catches it and emits a `TaskCompletionEvent(success = false)` — the
 * task fails immediately and visibly rather than silently disappearing.
 *
 * Example (Common code):
 * ```kotlin
 * class MyWorkerFactory : WorkerFactory {
 *     override fun createWorker(workerClassName: String): Worker {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> throw IllegalArgumentException("Unregistered worker: $workerClassName")
 *         }
 *     }
 * }
 * ```
 *
 * Replaces hardcoded worker registrations
 */
/**
 * Environment provided to a [Worker] during execution.
 *
 * Allows the worker to:
 * - Report progress via [progressListener]
 * - Check if execution has been cancelled via [isCancelled]
 */
class WorkerEnvironment(
    val progressListener: ProgressListener? = null,
    val isCancelled: () -> Boolean = { false }
)

/**
 * Platform-agnostic worker interface.
 *
 * Implement this interface for each type of background work.
 *
 * Example:
 * ```kotlin
 * class SyncWorker : Worker {
 *     override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
 *         // Report progress
 *         env.progressListener?.onProgressUpdate(WorkerProgress(50, "Halfway there"))
 *
 *         // Check cancellation
 *         if (env.isCancelled()) return WorkerResult.Failure("Cancelled")
 *
 *         return WorkerResult.Success("Done")
 *     }
 * }
 * ```
 */
interface Worker {
    /**
     * Performs the background work.
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @param env Environment providing progress reporting and cancellation checks
     * @return WorkerResult indicating success/failure
     */
    suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult

    /**
     * Called immediately after [doWork] returns.
     */
    fun close() {}
}

/**
 * Factory interface for creating worker instances.
 */
interface WorkerFactory {
    fun createWorker(workerClassName: String): Worker?
}

/**
 * A [WorkerFactory] that delegates to a list of other factories.
 *
 * Allows combining multiple libraries or modules that each provide their own workers.
 * The first factory that returns a non-null worker wins.
 */
class DelegatingWorkerFactory(
    private val factories: List<WorkerFactory>
) : WorkerFactory {
    override fun createWorker(workerClassName: String): Worker? {
        for (factory in factories) {
            val worker = factory.createWorker(workerClassName)
            if (worker != null) return worker
        }
        return null
    }
}
