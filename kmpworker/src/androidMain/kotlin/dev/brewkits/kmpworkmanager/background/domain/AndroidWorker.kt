package dev.brewkits.kmpworkmanager.background.domain

/**
 * Android-specific worker interface.
 *
 * Implement this interface for Android background workers.
 * Workers are executed through:
 * - KmpWorker: Deferrable tasks
 * - KmpHeavyWorker: Foreground service tasks (isHeavyTask = true)
 * - AlarmReceiver: Exact alarms
 *
 * Changed return type from Boolean to WorkerResult
 *
 * Example:
 * ```kotlin
 * class SyncWorker : AndroidWorker {
 *     override suspend fun doWork(input: String?): WorkerResult {
 *         return try {
 *             // Your sync logic here
 *             delay(2000)
 *             WorkerResult.Success(
 *                 message = "✅ Synced",
 *                 data = buildJsonObject { put("itemCount", 42) }
 *             )
 *         } catch (e: Exception) {
 *             WorkerResult.Failure("Sync failed: ${e.message}")
 *         }
 *     }
 * }
 * ```
 *
 * New interface replacing hardcoded workers in KmpWorker
 */
interface AndroidWorker : dev.brewkits.kmpworkmanager.background.domain.Worker {
    /**
     * Performs the background work.
     *
     * Return type changed from Boolean to WorkerResult
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return WorkerResult indicating success/failure with optional data and message
     */
    override suspend fun doWork(input: String?): WorkerResult
}
