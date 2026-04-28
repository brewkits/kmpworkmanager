package dev.brewkits.kmpworkmanager.background.domain

/**
 * Environment provided to a [Worker] during execution.
 *
 * Use this to:
 * - Report progress back to the UI via [progressListener].
 * - Check if the system has requested cancellation via [isCancelled].
 */
class WorkerEnvironment(
    val progressListener: ProgressListener? = null,
    val isCancelled: () -> Boolean = { false }
)

/**
 * The core interface for background tasks.
 *
 * Implement this interface to define your background logic. Your implementation
 * should be platform-agnostic whenever possible.
 *
 * Example:
 * ```kotlin
 * class SyncWorker : Worker {
 *     override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
 *         // 1. Check for cancellation early
 *         if (env.isCancelled()) return WorkerResult.Failure("Cancelled by OS")
 *
 *         // 2. Report progress
 *         env.progressListener?.onProgressUpdate(WorkerProgress(50, "Syncing data..."))
 *
 *         // 3. Return a success or failure result
 *         return WorkerResult.Success("Data synced successfully")
 *     }
 * }
 * ```
 */
interface Worker {
    /**
     * Executes the background task.
     *
     * @param input Optional JSON string passed during scheduling.
     * @param env Provides hooks for progress reporting and cancellation checks.
     * @return [WorkerResult] representing the outcome of the work.
     */
    suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult

    /**
     * Cleanup hook called immediately after [doWork] finishes, regardless of the result.
     * Use this to release resources like database connections or file handles.
     */
    fun close() {}
}

/**
 * Factory for instantiating workers by their class name.
 *
 * You must provide an implementation of this interface to KMP WorkManager so it can
 * map strings (workerClassName) to real object instances at runtime.
 */
interface WorkerFactory {
    /**
     * Returns a new instance of the worker class, or null if not handled by this factory.
     */
    fun createWorker(workerClassName: String): Worker?
}

/**
 * A composite factory that searches through multiple sub-factories.
 *
 * Useful for modular architectures where different feature modules provide their own workers.
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
