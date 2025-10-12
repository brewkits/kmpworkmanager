package com.example.kmpworkmanagerv2.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a background task completes.
 */
data class TaskCompletionEvent(
    val taskName: String,
    val success: Boolean,
    val message: String
)

/**
 * Global event bus for task completion events.
 * Workers can emit events here, and the UI can listen to them.
 */
object TaskEventBus {
    private val _events = MutableSharedFlow<TaskCompletionEvent>(replay = 1, extraBufferCapacity = 10)
    val events: SharedFlow<TaskCompletionEvent> = _events.asSharedFlow()

    suspend fun emit(event: TaskCompletionEvent) {
        println("🔔 TaskEventBus: Emitting event - ${event.taskName}: ${event.message}")
        val result = _events.tryEmit(event)
        println("🔔 TaskEventBus: Emit result = $result")
    }
}
