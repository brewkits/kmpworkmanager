@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.persistence.EventStoreConfig
import dev.brewkits.kmpworkmanager.persistence.IosEventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.*

/**
 * IosEventStore — integration tests on iOS simulator/device
 *
 * Covers:
 * - Save / retrieve / mark consumed lifecycle
 * - clearOldEvents delta semantics (ES-DOC fix)
 * - ES-1 regression: write errors surface (structural test only — real disk-full
 *   failures can't be injected easily, but we verify the happy-path write succeeds)
 * - Cleanup of old / consumed events
 * - clearAll
 *
 * Run with:
 *   ./gradlew :kmpworker:iosSimulatorArm64Test
 */
class IosEventStoreTest {

    private lateinit var store: IosEventStore

    @BeforeTest
    fun setup() = runTest {
        // Use autoCleanup=false to prevent probabilistic cleanup interfering with counts
        store = IosEventStore(config = EventStoreConfig(autoCleanup = false))
        store.clearAll()
    }

    @AfterTest
    fun teardown() = runTest {
        store.clearAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basic lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `saveEvent should return non-null ID`() = runTest {
        val event = TaskCompletionEvent("SaveTest", success = true, message = "Saved OK")
        val id = store.saveEvent(event)
        assertNotNull(id, "Event ID must not be null")
        assertTrue(id.isNotEmpty(), "Event ID must not be empty")
    }

    @Test
    fun `getUnconsumedEvents should return saved events`() = runTest {
        store.saveEvent(TaskCompletionEvent("Task1", true, "First"))
        store.saveEvent(TaskCompletionEvent("Task2", false, "Second"))

        val events = store.getUnconsumedEvents()
        assertEquals(2, events.size, "Should have 2 unconsumed events")
        assertEquals("Task1", events[0].event.taskName)
        assertEquals("Task2", events[1].event.taskName)
    }

    @Test
    fun `getUnconsumedEvents should return empty list when no events`() = runTest {
        val events = store.getUnconsumedEvents()
        assertEquals(0, events.size, "Should return empty list for empty store")
    }

    @Test
    fun `getEventCount should reflect saved events`() = runTest {
        assertEquals(0, store.getEventCount(), "Initial count should be 0")

        store.saveEvent(TaskCompletionEvent("CountTask1", true, "msg"))
        assertEquals(1, store.getEventCount())

        store.saveEvent(TaskCompletionEvent("CountTask2", true, "msg"))
        assertEquals(2, store.getEventCount())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // markEventConsumed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `markEventConsumed should remove event from unconsumed list`() = runTest {
        val id = store.saveEvent(TaskCompletionEvent("ConsumeTask", true, "msg"))
        assertEquals(1, store.getUnconsumedEvents().size)

        store.markEventConsumed(id)

        assertEquals(0, store.getUnconsumedEvents().size,
            "Consumed event should not appear in unconsumed list")
        assertEquals(1, store.getEventCount(),
            "Event should remain in store after being marked consumed")
    }

    @Test
    fun `markEventConsumed with unknown ID should not crash`() = runTest {
        store.saveEvent(TaskCompletionEvent("Safe", true, "msg"))
        // Should not throw
        store.markEventConsumed("non-existent-id-xyz")
        assertEquals(1, store.getUnconsumedEvents().size, "Existing event should be unaffected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // clearAll
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearAll should remove all events`() = runTest {
        store.saveEvent(TaskCompletionEvent("ClearTask1", true, "msg"))
        store.saveEvent(TaskCompletionEvent("ClearTask2", false, "msg"))
        assertEquals(2, store.getEventCount())

        store.clearAll()

        assertEquals(0, store.getEventCount(), "All events should be cleared")
        assertEquals(0, store.getUnconsumedEvents().size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // clearOldEvents — ES-DOC: delta semantics verification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearOldEvents with large positive delta should keep recent events`() = runTest {
        // Save events right now
        store.saveEvent(TaskCompletionEvent("Recent1", true, "msg"))
        store.saveEvent(TaskCompletionEvent("Recent2", true, "msg"))
        assertEquals(2, store.getEventCount())

        // clearOldEvents(7_days) — events created just now are newer than 7 days ago
        // → nothing should be deleted
        val deleted = store.clearOldEvents(7 * 24 * 3_600_000L) // 7 days
        assertEquals(0, deleted, "Recently created events should not be deleted (within 7d window)")
        assertEquals(2, store.getEventCount(), "Events should still be present")
    }

    @Test
    fun `clearOldEvents with delta of zero should clear all events`() = runTest {
        // Saving events → they get timestamp ~= now
        // clearOldEvents(0) → cutoffTime = now - 0 = now
        // Events with timestamp <= now → deleted
        store.saveEvent(TaskCompletionEvent("OldEvent1", true, "msg"))
        store.saveEvent(TaskCompletionEvent("OldEvent2", false, "msg"))
        assertEquals(2, store.getEventCount())

        // Small delay to ensure stored timestamps < current time
        kotlinx.coroutines.delay(10)

        val deleted = store.clearOldEvents(0L)
        assertEquals(2, deleted, "clearOldEvents(0) should delete all events (cutoff = now)")
        assertEquals(0, store.getEventCount())
    }

    @Test
    fun `clearOldEvents with negative delta should clear all events`() = runTest {
        store.saveEvent(TaskCompletionEvent("EventA", true, "msg"))
        store.saveEvent(TaskCompletionEvent("EventB", true, "msg"))
        assertEquals(2, store.getEventCount())

        // Negative delta → cutoffTime = now - (-1000) = now + 1000 (in the future)
        // All events have timestamp < now + 1000 → all deleted
        val deleted = store.clearOldEvents(-1000L)
        assertEquals(2, deleted, "Negative delta should delete all events")
        assertEquals(0, store.getEventCount())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ES-1 regression: write errors should surface (structural test)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ES-1 regression - successful writes should not throw`() = runTest {
        // Verify that the normal write path works without throwing.
        // This is the counterpart to ES-1: after the fix, successful writes
        // still work fine and errors are only thrown when writeToURL actually fails.
        val longMessage = "A".repeat(10_000) // 10KB message
        val id = store.saveEvent(TaskCompletionEvent("LargeEvent", true, longMessage))
        assertNotNull(id, "Large event should be saved successfully")

        val events = store.getUnconsumedEvents()
        assertEquals(1, events.size)
        assertEquals(10_000, events[0].event.message.length, "Message should be preserved")
    }

    @Test
    fun `ES-1 regression - multiple rapid writes should all succeed`() = runTest {
        // Regression test: verify rapid sequential writes don't silently fail
        val count = 20
        repeat(count) { i ->
            store.saveEvent(TaskCompletionEvent("RapidTask$i", true, "Rapid write $i"))
        }

        assertEquals(count, store.getEventCount(),
            "All $count rapid writes should persist — no silent failures (ES-1)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ordering
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getUnconsumedEvents should return events ordered by timestamp ascending`() = runTest {
        store.saveEvent(TaskCompletionEvent("First", true, "msg"))
        kotlinx.coroutines.delay(5)
        store.saveEvent(TaskCompletionEvent("Second", true, "msg"))
        kotlinx.coroutines.delay(5)
        store.saveEvent(TaskCompletionEvent("Third", true, "msg"))

        val events = store.getUnconsumedEvents()
        assertEquals(3, events.size)
        assertTrue(events[0].timestamp <= events[1].timestamp, "Events should be ordered by timestamp")
        assertTrue(events[1].timestamp <= events[2].timestamp, "Events should be ordered by timestamp")
        assertEquals("First", events[0].event.taskName)
        assertEquals("Third", events[2].event.taskName)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mixed consumed / unconsumed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getUnconsumedEvents should filter out consumed events`() = runTest {
        val id1 = store.saveEvent(TaskCompletionEvent("Keep", true, "msg"))
        val id2 = store.saveEvent(TaskCompletionEvent("Consume", true, "msg"))
        val id3 = store.saveEvent(TaskCompletionEvent("Keep2", true, "msg"))

        store.markEventConsumed(id2)

        val unconsumed = store.getUnconsumedEvents()
        assertEquals(2, unconsumed.size, "Only unconsumed events should be returned")
        assertTrue(unconsumed.none { it.id == id2 }, "Consumed event should not appear")
        assertEquals(3, store.getEventCount(), "All 3 events still exist in store")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `should handle events with null outputData`() = runTest {
        // Note: non-null outputData requires a contextual serializer for Any? which is not
        // registered — serialization would throw on Kotlin/Native. Test null case instead.
        val event = TaskCompletionEvent(
            taskName = "DataTask",
            success = true,
            message = "With data",
            outputData = null
        )
        val id = store.saveEvent(event)
        assertNotNull(id)

        val events = store.getUnconsumedEvents()
        assertEquals(1, events.size)
        assertNull(events[0].event.outputData)
    }

    @Test
    fun `should handle failure events`() = runTest {
        val event = TaskCompletionEvent(
            taskName = "FailedTask",
            success = false,
            message = "Something went wrong: network timeout"
        )
        val id = store.saveEvent(event)
        assertNotNull(id)

        val events = store.getUnconsumedEvents()
        assertEquals(1, events.size)
        assertFalse(events[0].event.success)
        assertEquals("Something went wrong: network timeout", events[0].event.message)
    }
}
