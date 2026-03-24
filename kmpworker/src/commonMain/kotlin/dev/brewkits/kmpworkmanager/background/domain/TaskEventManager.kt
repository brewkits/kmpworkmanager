package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlin.concurrent.Volatile

/**
 * Central manager for task completion events.
 *
 * Responsibilities:
 * - Persists events to storage for zero event loss
 * - Emits events to EventBus for live UI updates
 *
 * Usage:
 * ```kotlin
 * // In worker after task completion
 * TaskEventManager.emit(TaskCompletionEvent(
 *     taskName = "MyTask",
 *     success = true,
 *     message = "Task completed successfully"
 * ))
 * ```
 */
object TaskEventManager {

    // @Volatile ensures writes from the initializing thread are immediately visible
    // to any thread that subsequently reads eventStore (e.g. a worker's IO thread).
    @Volatile
    private var eventStore: EventStore? = null

    /**
     * Initializes the event manager with an EventStore implementation.
     * Must be called during app initialization (before any workers run).
     *
     * Duplicate calls are silently ignored — the first call wins.
     *
     * @param store The EventStore instance to use for persistence
     */
    fun initialize(store: EventStore) {
        if (eventStore != null) {
            Logger.w(LogTags.SCHEDULER, "TaskEventManager: Already initialized — ignoring duplicate call")
            return
        }
        eventStore = store
        Logger.i(LogTags.SCHEDULER, "TaskEventManager: Initialized with EventStore")
    }

    /**
     * Resets the event manager state. For use in tests only.
     * @suppress
     */
    internal fun resetForTest() {
        eventStore = null
    }

    /**
     * Emits a task completion event.
     *
     * Flow:
     * 1. Saves event to persistent storage (survives app restart)
     * 2. Emits event to EventBus (for live UI)
     *
     * @param event The task completion event to emit
     * @return Event ID if saved successfully, null otherwise
     */
    suspend fun emit(event: TaskCompletionEvent): String? {
        try {
            // 1. Save to persistent storage
            val eventId = eventStore?.saveEvent(event)

            if (eventId != null) {
                Logger.d(LogTags.SCHEDULER, "TaskEventManager: Saved event $eventId for task ${event.taskName}")
            } else {
                Logger.w(LogTags.SCHEDULER, "TaskEventManager: EventStore not initialized, event not persisted")
            }

            // 2. Emit to EventBus for live UI (best effort)
            TaskEventBus.emit(event)

            return eventId
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "TaskEventManager: Failed to emit event", e)

            // Still try to emit to EventBus even if persistence failed
            try {
                TaskEventBus.emit(event)
            } catch (busError: Exception) {
                Logger.e(LogTags.SCHEDULER, "TaskEventManager: EventBus emit also failed", busError)
            }

            return null
        }
    }
}
