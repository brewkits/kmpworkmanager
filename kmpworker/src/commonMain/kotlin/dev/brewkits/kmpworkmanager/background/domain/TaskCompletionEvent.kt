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

    // NOTE: there is intentionally no resetForTest() here (unlike TaskEventManager, which
    // has a real one). A prior version of this function was a complete no-op — it did not
    // reset the replay cache despite its name and KDoc promising test isolation, and had
    // zero callers. Its name invited exactly the kind of silent event bleed-through between
    // tests that this project's own testing conventions warn against. Tests that need
    // isolation should collect from a fresh subscriber per test (see the pattern in
    // TaskEventTest) rather than relying on a bus-level reset.
}
