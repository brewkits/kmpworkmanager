package dev.brewkits.kmpworkmanager.sample.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a background task completes.
 *
 * v2.3.0+: Added outputData field to support returning data from workers
 */
data class TaskCompletionEvent(
    val taskName: String,
    val success: Boolean,
    val message: String,
    val outputData: Map<String, Any?>? = null
)

/**
 * Global event bus for task completion events.
 * Workers can emit events here, and the UI can listen to them.
 */
object TaskEventBus {
    private val _events = MutableSharedFlow<TaskCompletionEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<TaskCompletionEvent> = _events.asSharedFlow()

    suspend fun emit(event: TaskCompletionEvent) {
        _events.tryEmit(event)
    }
}
