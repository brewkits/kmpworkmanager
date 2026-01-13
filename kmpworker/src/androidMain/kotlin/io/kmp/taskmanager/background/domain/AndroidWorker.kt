package io.kmp.taskmanager.background.domain

/**
 * Android-specific worker interface.
 *
 * Implement this interface for Android background workers.
 * Workers are executed through:
 * - KmpWorker: Deferrable tasks
 * - KmpHeavyWorker: Foreground service tasks (isHeavyTask = true)
 * - AlarmReceiver: Exact alarms
 *
 * Example:
 * ```kotlin
 * class SyncWorker : AndroidWorker {
 *     override suspend fun doWork(input: String?): Boolean {
 *         // Your sync logic here
 *         delay(2000)
 *         TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
 *         return true
 *     }
 * }
 * ```
 *
 * v4.0.0+: New interface replacing hardcoded workers in KmpWorker
 */
interface AndroidWorker : io.kmp.taskmanager.background.domain.Worker {
    /**
     * Performs the background work.
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @return true if work completed successfully, false otherwise
     */
    override suspend fun doWork(input: String?): Boolean
}
