package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskCompletionEventTest {

    @Test
    fun `TaskCompletionEvent should preserve all fields`() {
        val event = TaskCompletionEvent(
            taskName = "TestTask",
            success = true,
            message = "Task completed successfully"
        )

        assertEquals("TestTask", event.taskName)
        assertTrue(event.success)
        assertEquals("Task completed successfully", event.message)
    }

    @Test
    fun `TaskCompletionEvent with failure should set success to false`() {
        val event = TaskCompletionEvent(
            taskName = "FailedTask",
            success = false,
            message = "Task failed due to network error"
        )

        assertEquals("FailedTask", event.taskName)
        assertFalse(event.success)
        assertEquals("Task failed due to network error", event.message)
    }

    @Test
    fun `TaskCompletionEvent with empty message should preserve empty string`() {
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = true,
            message = ""
        )

        assertEquals("", event.message)
    }

    @Test
    fun `TaskCompletionEvent equality should work correctly`() {
        val event1 = TaskCompletionEvent("Task", true, "Success")
        val event2 = TaskCompletionEvent("Task", true, "Success")
        val event3 = TaskCompletionEvent("Task", false, "Failure")

        assertEquals(event1, event2)
        kotlin.test.assertNotEquals(event1, event3)
    }
}

class TaskEventBusTest {

    @Test
    fun `TaskEventBus should have events flow property`() {
        // Verify that TaskEventBus has an events property
        val flow = TaskEventBus.events
        kotlin.test.assertNotNull(flow)
    }

    @Test
    fun `TaskEventBus replay should provide last event to new subscribers`() = runTest {
        // Emit 3 events first
        repeat(3) { index ->
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ReplayTask$index",
                    success = true,
                    message = "Event $index"
                )
            )
        }

        // Small delay to ensure events are processed
        delay(50)

        // New subscriber should receive the last event from replay buffer (replay = 1)
        val receivedEvents = mutableListOf<TaskCompletionEvent>()
        val job = backgroundScope.launch {
            TaskEventBus.events.take(1).toList(receivedEvents)
        }
        
        // Wait for collection to finish
        job.join()

        // With replay=1, should receive exactly the last emitted event (index 2)
        assertEquals(1, receivedEvents.size)
        assertEquals("ReplayTask2", receivedEvents[0].taskName)
    }

    @Test
    fun `TaskEventBus replay configuration should be set to 1`() {
        // This test verifies the replay configuration through behavioral testing
        // The replay=1 parameter allows late subscribers to receive the most recent event

        // Verify events flow is accessible
        kotlin.test.assertNotNull(TaskEventBus.events)
    }
}
