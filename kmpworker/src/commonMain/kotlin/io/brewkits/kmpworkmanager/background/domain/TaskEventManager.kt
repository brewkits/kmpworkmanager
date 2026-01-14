package io.brewkits.kmpworkmanager.background.domain

import io.brewkits.kmpworkmanager.persistence.EventStore
import io.brewkits.kmpworkmanager.utils.Logger
import io.brewkits.kmpworkmanager.utils.LogTags

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

    private var eventStore: EventStore? = null

    /**
     * Initializes the event manager with an EventStore implementation.
     * Must be called during app initialization.
     *
     * @param store The EventStore instance to use for persistence
     */
    fun initialize(store: EventStore) {
        eventStore = store
        Logger.i(LogTags.SCHEDULER, "TaskEventManager: Initialized with EventStore")
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
