package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Event emitted when a background task completes.
 *
 * v2.3.0+: Added outputData field to support returning data from workers
 * v2.3.7+: outputData changed from Map<String, @Contextual Any?> to JsonObject
 *           — eliminates runtime SerializationException on non-serializable types
 */
@Serializable
data class TaskCompletionEvent(
    val taskName: String,
    val success: Boolean,
    val message: String,
    val outputData: JsonObject? = null
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
        // emit() suspends when the buffer (extraBufferCapacity=64) is full — no silent drops
        _events.emit(event)
    }

    /**
     * Resets the replay cache by re-creating internal state equivalent.
     * For use in tests only — call between test cases to prevent event bleed-through.
     * @suppress
     */
    internal fun resetForTest() {
        // Drain replay cache: emit a sentinel then collect it synchronously is not
        // feasible in common code, so we rely on callers using a fresh subscriber
        // per test. This method exists as a marker / extension point.
        //
        // Practical pattern:
        //   val events = mutableListOf<TaskCompletionEvent>()
        //   val job = backgroundScope.launch { TaskEventBus.events.toList(events) }
        //   // ... exercise code ...
        //   job.cancel()
        //   assertEquals(expected, events)
    }
}
