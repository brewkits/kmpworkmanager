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
import kotlin.test.Ignore
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
 * Note: These tests are currently @Ignore'd for unit tests because they require
 * Android Log to be mocked (which needs Robolectric). The actual code works fine
 * in Android instrumented tests and on actual devices.
 */
@Ignore
class EventPersistenceIntegrationTest {

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
    fun `TaskEventManager should save event and emit to EventBus`() = runTest {
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
    fun `EventSyncManager should replay missed events to EventBus`() = runTest {
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
    fun `End-to-end flow should work correctly`() = runTest {
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
    fun `EventSyncManager should handle empty store gracefully`() = runTest {
        val store = InMemoryEventStore()

        // Sync with empty store
        val syncedCount = EventSyncManager.syncEvents(store)

        assertEquals(0, syncedCount, "Should have synced 0 events")
    }

    @Test
    fun `EventSyncManager clearOldEvents should remove old events`() = runTest {
        val store = InMemoryEventStore()

        // Add events
        store.saveEvent(TaskCompletionEvent("Task1", true, "Message1"))
        store.saveEvent(TaskCompletionEvent("Task2", true, "Message2"))

        assertEquals(2, store.getEventCount(), "Should have 2 events initially")

        // Clear events older than "now" (should delete nothing since events are new)
        val deletedCount1 = EventSyncManager.clearOldEvents(store, 0)
        assertEquals(0, deletedCount1, "Should delete 0 events")
        assertEquals(2, store.getEventCount(), "Should still have 2 events")

        // Clear events older than "future" (should delete all)
        val deletedCount2 = EventSyncManager.clearOldEvents(store, -1000)
        assertEquals(2, deletedCount2, "Should delete 2 events")
        assertEquals(0, store.getEventCount(), "Should have 0 events")
    }

    @Test
    fun `TaskEventManager should handle EventStore initialization gracefully`() = runTest {
        // Don't initialize TaskEventManager

        val event = TaskCompletionEvent("Test", true, "Message")

        // Should not crash even if EventStore is not initialized
        val eventId = TaskEventManager.emit(event)

        // Event ID will be null since store is not initialized
        // But EventBus should still receive the event
        assertEquals(null, eventId, "Event ID should be null when store not initialized")
    }

    @Test
    fun `Multiple events should maintain order`() = runTest {
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
    fun `markEventConsumed should mark event as consumed`() = runTest {
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
    fun `clearAll should remove all events`() = runTest {
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
