package io.brewkits.kmpworkmanager.background.domain

import io.brewkits.kmpworkmanager.persistence.EventStore
import io.brewkits.kmpworkmanager.utils.Logger
import io.brewkits.kmpworkmanager.utils.LogTags

/**
 * Manager for synchronizing missed events on app launch.
 *
 * When the app starts, this manager retrieves all unconsumed events
 * from persistent storage and replays them to the EventBus so the UI
 * can process events that were emitted while the app was not running.
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate() (Android) or @main (iOS)
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         // Initialize EventStore first
 *         val eventStore = AndroidEventStore(this)
 *         TaskEventManager.initialize(eventStore)
 *
 *         // Sync missed events
 *         lifecycleScope.launch {
 *             EventSyncManager.syncEvents(eventStore)
 *         }
 *     }
 * }
 * ```
 */
object EventSyncManager {

    /**
     * Synchronizes missed events from persistent storage to EventBus.
     *
     * Flow:
     * 1. Retrieves all unconsumed events from EventStore
     * 2. Replays them to EventBus in chronological order
     * 3. Logs sync statistics
     *
     * @param eventStore The EventStore instance to sync from
     * @return Number of events synchronized
     */
    suspend fun syncEvents(eventStore: EventStore): Int {
        return try {
            // Get all unconsumed events (sorted by timestamp, oldest first)
            val missedEvents = eventStore.getUnconsumedEvents()

            if (missedEvents.isEmpty()) {
                Logger.d(LogTags.SCHEDULER, "EventSyncManager: No missed events to sync")
                return 0
            }

            Logger.i(LogTags.SCHEDULER, "EventSyncManager: Syncing ${missedEvents.size} missed events")

            // Replay events to EventBus
            var successCount = 0
            missedEvents.forEach { storedEvent ->
                try {
                    TaskEventBus.emit(storedEvent.event)
                    successCount++

                    Logger.d(
                        LogTags.SCHEDULER,
                        "EventSyncManager: Replayed event ${storedEvent.id} for task ${storedEvent.event.taskName}"
                    )
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.SCHEDULER,
                        "EventSyncManager: Failed to replay event ${storedEvent.id}",
                        e
                    )
                }
            }

            Logger.i(
                LogTags.SCHEDULER,
                "EventSyncManager: Synced $successCount/${missedEvents.size} events"
            )

            successCount
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "EventSyncManager: Sync failed", e)
            0
        }
    }

    /**
     * Clears old events from storage.
     * Useful for periodic cleanup or manual maintenance.
     *
     * @param eventStore The EventStore instance
     * @param olderThanMs Events older than this timestamp will be deleted
     * @return Number of events deleted
     */
    suspend fun clearOldEvents(eventStore: EventStore, olderThanMs: Long): Int {
        return try {
            val deletedCount = eventStore.clearOldEvents(olderThanMs)

            if (deletedCount > 0) {
                Logger.i(LogTags.SCHEDULER, "EventSyncManager: Cleared $deletedCount old events")
            }

            deletedCount
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "EventSyncManager: Failed to clear old events", e)
            0
        }
    }
}
