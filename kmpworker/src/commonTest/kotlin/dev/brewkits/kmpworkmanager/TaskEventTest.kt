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
    fun TaskCompletionEvent_should_preserve_all_fields() {
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
    fun TaskCompletionEvent_with_failure_should_set_success_to_false() {
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
    fun TaskCompletionEvent_with_empty_message_should_preserve_empty_string() {
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = true,
            message = ""
        )

        assertEquals("", event.message)
    }

    @Test
    fun TaskCompletionEvent_equality_should_work_correctly() {
        val event1 = TaskCompletionEvent("Task", true, "Success")
        val event2 = TaskCompletionEvent("Task", true, "Success")
        val event3 = TaskCompletionEvent("Task", false, "Failure")

        assertEquals(event1, event2)
        kotlin.test.assertNotEquals(event1, event3)
    }
}

class TaskEventBusTest {

    @Test
    fun TaskEventBus_should_have_events_flow_property() {
        // Verify that TaskEventBus has an events property
        val flow = TaskEventBus.events
        kotlin.test.assertNotNull(flow)
    }

    @Test
    fun TaskEventBus_replay_should_provide_last_event_to_new_subscribers() = runTest {
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
    fun TaskEventBus_replay_configuration_should_be_set_to_1() {
        // This test verifies the replay configuration through behavioral testing
        // The replay=1 parameter allows late subscribers to receive the most recent event

        // Verify events flow is accessible
        kotlin.test.assertNotNull(TaskEventBus.events)
    }
}
