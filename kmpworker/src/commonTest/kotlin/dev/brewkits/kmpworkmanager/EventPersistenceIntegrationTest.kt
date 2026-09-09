package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.EventSyncManager
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.EventStoreConfig
import dev.brewkits.kmpworkmanager.persistence.StoredEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Event Persistence System.
 *
 * Tests the complete flow:
 * - TaskEventManager.emit() → EventStore + EventBus
 * - EventSyncManager.syncEvents() → Replay missed events
 * - End-to-end event lifecycle
 *
 * **Previously `@Ignore`d on this class, for a reason that was not true.** The note said the
 * tests "require Android Log to be mocked (which needs Robolectric)". They do not:
 * `kmpworker/build.gradle.kts` sets `unitTests.isReturnDefaultValues = true`, so
 * `android.util.Log` returns defaults instead of throwing, and the iOS side never had the
 * problem at all. Removing the annotation ran 9 tests, of which 5 passed immediately.
 *
 * The 4 that failed were real defects in this file, hidden for as long as the class was
 * ignored:
 *  - Three assumed each test got its own [TaskEventManager]. It is a singleton whose
 *    `initialize` is compare-and-set "first call wins", so the second test onwards silently
 *    kept the FIRST test's store and their own `store` never saw an event. Fixed with the
 *    reset in [setUp]/[tearDown] — the pattern every other test touching this singleton uses.
 *  - One asserted the old meaning of `clearOldEvents`. See that test for the detail.
 */
class EventPersistenceIntegrationTest {

    @BeforeTest
    fun setUp() {
        // TaskEventManager.initialize is first-call-wins by design, so without this a test
        // inherits whichever store ran first in the class.
        TaskEventManager.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        // Leave nothing behind for the next class in the same JVM / test binary.
        TaskEventManager.resetForTest()
    }

    /**
     * In-memory EventStore for testing
     */
    private class InMemoryEventStore : EventStore {
        private val events = mutableListOf<StoredEvent>()
        private var idCounter = 0
        private var timestampCounter = 1000L

        override suspend fun saveEvent(event: TaskCompletionEvent): String {
            val eventId = "event-${idCounter++}"
            events.add(
                StoredEvent(
                    id = eventId,
                    event = event,
                    timestamp = timestampCounter++,
                    consumed = false
                )
            )
            return eventId
        }

        override suspend fun getUnconsumedEvents(): List<StoredEvent> {
            return events.filter { !it.consumed }.sortedBy { it.timestamp }
        }

        override suspend fun markEventConsumed(eventId: String) {
            val index = events.indexOfFirst { it.id == eventId }
            if (index != -1) {
                events[index] = events[index].copy(consumed = true)
            }
        }

        override suspend fun clearOldEvents(olderThanMs: Long): Int {
            val now = timestampCounter
            val cutoffTime = now - olderThanMs
            val toRemove = events.filter { it.timestamp < cutoffTime }
            events.removeAll(toRemove)
            return toRemove.size
        }

        override suspend fun clearAll() {
            events.clear()
        }

        override suspend fun getEventCount(): Int {
            return events.size
        }
    }

    @Test
    fun TaskEventManager_should_save_event_and_emit_to_EventBus() = runTest {
        val store = InMemoryEventStore()
        TaskEventManager.initialize(store)

        val event = TaskCompletionEvent(
            taskName = "TestTask",
            success = true,
            message = "Test completed"
        )

        // Emit event
        val eventId = TaskEventManager.emit(event)

        // Verify event was saved
        assertNotNull(eventId, "Event ID should not be null")
        val unconsumed = store.getUnconsumedEvents()
        assertEquals(1, unconsumed.size, "Should have 1 unconsumed event")
        assertEquals("TestTask", unconsumed[0].event.taskName)
    }

    @Test
    fun EventSyncManager_should_replay_missed_events_to_EventBus() = runTest {
        val store = InMemoryEventStore()

        // Save events directly to store (simulating events emitted while app was closed)
        store.saveEvent(TaskCompletionEvent("Task1", true, "Message1"))
        store.saveEvent(TaskCompletionEvent("Task2", false, "Message2"))
        store.saveEvent(TaskCompletionEvent("Task3", true, "Message3"))

        // Sync events
        val syncedCount = EventSyncManager.syncEvents(store)

        // Verify all events were synced
        assertEquals(3, syncedCount, "Should have synced 3 events")
    }

    @Test
    fun End_to_end_flow_should_work_correctly() = runTest {
        val store = InMemoryEventStore()
        TaskEventManager.initialize(store)

        // Step 1: Emit events while "app is running"
        TaskEventManager.emit(TaskCompletionEvent("Task1", true, "Done"))
        TaskEventManager.emit(TaskCompletionEvent("Task2", true, "Done"))

        delay(100) // Small delay to ensure events are saved

        // Step 2: Verify events are persisted
        val stored = store.getUnconsumedEvents()
        assertEquals(2, stored.size, "Should have 2 stored events")

        // Step 3: Simulate app restart - sync missed events
        val syncedCount = EventSyncManager.syncEvents(store)
        assertEquals(2, syncedCount, "Should have synced 2 events")

        // Step 4: Verify events are still in store (not auto-consumed)
        val stillStored = store.getUnconsumedEvents()
        assertEquals(2, stillStored.size, "Events should still be in store")
    }

    @Test
    fun EventSyncManager_should_handle_empty_store_gracefully() = runTest {
        val store = InMemoryEventStore()

        // Sync with empty store
        val syncedCount = EventSyncManager.syncEvents(store)

        assertEquals(0, syncedCount, "Should have synced 0 events")
    }

    @Test
    fun EventSyncManager_clearOldEvents_should_remove_old_events() = runTest {
        val store = InMemoryEventStore()

        // Add events
        store.saveEvent(TaskCompletionEvent("Task1", true, "Message1"))
        store.saveEvent(TaskCompletionEvent("Task2", true, "Message2"))

        assertEquals(2, store.getEventCount(), "Should have 2 events initially")

        // `olderThanMs` is an AGE, not a cutoff timestamp: EventStore.clearOldEvents documents
        // "events older than this duration are deleted" (pass 86_400_000 for 24 h). This test
        // used to read it as a cutoff and assert that `0` deletes nothing — the opposite of
        // the contract, since every event is older than zero milliseconds. It was only ever
        // green because the whole class was @Ignore'd.
        val keptCount = EventSyncManager.clearOldEvents(store, 10_000)
        assertEquals(0, keptCount, "A 10s retention window must keep events that were just written")
        assertEquals(2, store.getEventCount(), "Should still have 2 events")

        val deletedCount = EventSyncManager.clearOldEvents(store, 0)
        assertEquals(2, deletedCount, "A zero-length retention window deletes everything")
        assertEquals(0, store.getEventCount(), "Should have 0 events")
    }

    @Test
    fun TaskEventManager_should_handle_EventStore_initialization_gracefully() = runTest {
        // Don't initialize TaskEventManager

        val event = TaskCompletionEvent("Test", true, "Message")

        // Should not crash even if EventStore is not initialized
        val eventId = TaskEventManager.emit(event)

        // Event ID will be null since store is not initialized
        // But EventBus should still receive the event
        assertEquals(null, eventId, "Event ID should be null when store not initialized")
    }

    @Test
    fun Multiple_events_should_maintain_order() = runTest {
        val store = InMemoryEventStore()
        TaskEventManager.initialize(store)

        // Emit events in sequence
        TaskEventManager.emit(TaskCompletionEvent("Task1", true, "First"))
        delay(10) // Small delay to ensure different timestamps
        TaskEventManager.emit(TaskCompletionEvent("Task2", true, "Second"))
        delay(10)
        TaskEventManager.emit(TaskCompletionEvent("Task3", true, "Third"))

        delay(100) // Allow time for events to be saved

        // Verify order is maintained
        val events = store.getUnconsumedEvents()
        assertEquals(3, events.size)
        assertEquals("Task1", events[0].event.taskName)
        assertEquals("Task2", events[1].event.taskName)
        assertEquals("Task3", events[2].event.taskName)
        assertTrue(events[0].timestamp <= events[1].timestamp)
        assertTrue(events[1].timestamp <= events[2].timestamp)
    }

    @Test
    fun markEventConsumed_should_mark_event_as_consumed() = runTest {
        val store = InMemoryEventStore()

        val eventId = store.saveEvent(TaskCompletionEvent("Task1", true, "Message"))

        // Verify unconsumed
        assertEquals(1, store.getUnconsumedEvents().size)

        // Mark as consumed
        store.markEventConsumed(eventId)

        // Verify consumed
        assertEquals(0, store.getUnconsumedEvents().size)
        assertEquals(1, store.getEventCount(), "Event should still be in store, just marked consumed")
    }

    @Test
    fun clearAll_should_remove_all_events() = runTest {
        val store = InMemoryEventStore()

        // Add events
        store.saveEvent(TaskCompletionEvent("Task1", true, "Message1"))
        store.saveEvent(TaskCompletionEvent("Task2", true, "Message2"))
        store.saveEvent(TaskCompletionEvent("Task3", true, "Message3"))

        assertEquals(3, store.getEventCount())

        // Clear all
        store.clearAll()

        assertEquals(0, store.getEventCount())
        assertEquals(0, store.getUnconsumedEvents().size)
    }
}
