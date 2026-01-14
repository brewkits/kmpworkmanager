package io.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Event emitted when a background task completes.
 */
@Serializable
data class TaskCompletionEvent(
    val taskName: String,
    val success: Boolean,
    val message: String
)

/**
 * Global event bus for task completion events.
 * Workers can emit events here, and the UI can listen to them.
 *
 * Configuration:
 * - replay=5: Keeps last 5 events in memory for late subscribers (~1-2 minutes of history)
 * - extraBufferCapacity=64: Additional buffer for high-frequency events
 *
 * Note: For long-term event persistence across app restarts, see EventStore (Issue #1).
 */
object TaskEventBus {
    private val _events = MutableSharedFlow<TaskCompletionEvent>(
        replay = 5,  // Keep last 5 events for late subscribers
        extraBufferCapacity = 64
    )
    val events: SharedFlow<TaskCompletionEvent> = _events.asSharedFlow()

    suspend fun emit(event: TaskCompletionEvent) {
        _events.tryEmit(event)
    }
}
