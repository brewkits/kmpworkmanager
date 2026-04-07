package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Event emitted when a background task completes.
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
 * Configuration rationale:
 * - replay=1: One event cached for late subscribers — enough for UI to show latest result
 *   without holding multiple large outputData JsonObjects in RAM per subscriber.
 * - extraBufferCapacity=32: Burst buffer for concurrent workers finishing simultaneously.
 * - onBufferOverflow=DROP_OLDEST: Workers never block waiting for slow UI consumers.
 *   DROP_OLDEST is safe here because completion events are idempotent notifications —
 *   the durable record lives in AndroidEventStore/IosFileStorage, not in this bus.
 *
 * Note: For long-term event persistence across app restarts, see EventStore.
 */
object TaskEventBus {
    private val _events = MutableSharedFlow<TaskCompletionEvent>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TaskCompletionEvent> = _events.asSharedFlow()

    fun emit(event: TaskCompletionEvent) {
        // tryEmit always succeeds with DROP_OLDEST overflow policy — never blocks workers.
        _events.tryEmit(event)
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
