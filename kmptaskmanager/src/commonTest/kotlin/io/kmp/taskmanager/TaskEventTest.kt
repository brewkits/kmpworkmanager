package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.background.domain.TaskEventBus
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
}
