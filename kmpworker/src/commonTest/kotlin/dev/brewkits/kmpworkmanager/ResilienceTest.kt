package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.StoredEvent
import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Resilience and Sustainability Tests.
 */
class ResilienceTest {

    private class FakeEventStore : EventStore {
        val events = mutableListOf<StoredEvent>()
        override suspend fun saveEvent(event: TaskCompletionEvent): String {
            val id = "event-${events.size}"
            events.add(StoredEvent(id, event, currentTimeMillis()))
            return id
        }
        override suspend fun getUnconsumedEvents(): List<StoredEvent> = events.filter { !it.consumed }
        override suspend fun markEventConsumed(eventId: String) {
            val index = events.indexOfFirst { it.id == eventId }
            if (index != -1) events[index] = events[index].copy(consumed = true)
        }
        override suspend fun clearOldEvents(olderThanMs: Long): Int = 0
        override suspend fun clearAll() = events.clear()
        override suspend fun getEventCount(): Int = events.size
    }

    @Test
    fun testDurableEventsPersistence() = runTest {
        val store = FakeEventStore()
        TaskEventManager.initialize(store)

        val event = TaskCompletionEvent(
            taskName = "TestTask",
            success = true,
            message = "Success!"
        )

        // Emit via manager
        TaskEventManager.emit(event)

        // Verify it's in the store (Durable)
        assertEquals(1, store.getEventCount())
        assertEquals("TestTask", store.events.first().event.taskName)

        // Verify it's also on the live bus
        val liveEvent = TaskEventBus.events.first()
        assertEquals("TestTask", liveEvent.taskName)
    }

    @Test
    fun testTimeUtilsReliability() {
        val t1 = currentTimeMillis()
        // Simple spin-wait to ensure time moves forward
        val start = t1
        while (currentTimeMillis() == start) { /* wait */ }
        val t2 = currentTimeMillis()
        
        assertTrue(t2 > t1, "Time must move forward: $t2 > $t1")
    }
}
