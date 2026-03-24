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
interface WorkerFactory {
    /**
     * Creates a worker instance based on the class name.
     *
     * Return `null` to signal that this factory does not handle [workerClassName].
     * Throw [IllegalArgumentException] only when the class name is known but invalid.
     *
     * @param workerClassName The fully qualified class name or simple name
     * @return Worker instance, or null if this factory does not handle the class name
     * @throws IllegalArgumentException if [workerClassName] is recognised but invalid
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
 * Changed return type from Boolean to WorkerResult for richer return values
 *
 * Example:
 * ```kotlin
 * class SyncWorker : Worker {
 *     override suspend fun doWork(input: String?): WorkerResult {
 *         return try {
 *             // Your sync logic here
 *             delay(2000)
 *             WorkerResult.Success(
 *                 message = "Sync completed",
 *                 data = buildJsonObject { put("syncedItems", 42) }
 *             )
 *         } catch (e: Exception) {
 *             WorkerResult.Failure("Sync failed: ${e.message}")
 *         }
 *     }
 * }
 * ```
 */
interface Worker {
    /**
     * Performs the background work.
     *
     * Return type changed from Boolean to WorkerResult
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return WorkerResult indicating success/failure with optional data and message
     */
    suspend fun doWork(input: String?): WorkerResult

    /**
     * Called by the executor immediately after [doWork] returns (success, failure, or timeout).
     *
     * Override to release resources held by this worker instance — file handles, HTTP clients,
     * large in-memory buffers, etc. The default no-op is safe to ignore for lightweight workers.
     *
     * **Contract:**
     * - Called exactly once per worker instance, even if [doWork] throws.
     * - Guaranteed to run under [kotlinx.coroutines.NonCancellable] so it is not skipped
     *   during coroutine cancellation.
     * - Must complete quickly (< 500ms); long-running cleanup should be dispatched separately.
     *
     * Example:
     * ```kotlin
     * class VideoProcessingWorker : Worker {
     *     private val tempFile = createTempFile()
     *
     *     override suspend fun doWork(input: String?): WorkerResult {
     *         // ... process video using tempFile ...
     *         return WorkerResult.Success()
     *     }
     *
     *     override fun close() {
     *         tempFile.delete()  // Always cleaned up, even on failure or timeout
     *     }
     * }
     * ```
     */
    fun close() {}
}
