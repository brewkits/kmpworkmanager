package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EdgeCasesTest {

    @Test
    fun `TaskTrigger OneTime with negative delay should accept value`() {
        val trigger = TaskTrigger.OneTime(initialDelayMs = -1000)
        assertEquals(-1000L, trigger.initialDelayMs)
    }

    @Test
    fun `TaskTrigger Periodic with zero interval should accept value`() {
        val trigger = TaskTrigger.Periodic(intervalMs = 0)
        assertEquals(0L, trigger.intervalMs)
    }

    @Test
    fun `TaskTrigger Periodic with negative interval should accept value`() {
        val trigger = TaskTrigger.Periodic(intervalMs = -1000)
        assertEquals(-1000L, trigger.intervalMs)
    }

    @Test
    fun `TaskTrigger Periodic with zero flex should accept value`() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000, flexMs = 0)
        assertEquals(0L, trigger.flexMs)
    }

    @Test
    fun `TaskTrigger Periodic with negative flex should accept value`() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000, flexMs = -1000)
        assertEquals(-1000L, trigger.flexMs)
    }

    @Test
    fun `TaskTrigger Exact with zero timestamp should accept value`() {
        val trigger = TaskTrigger.Exact(atEpochMillis = 0)
        assertEquals(0L, trigger.atEpochMillis)
    }

    @Test
    fun `TaskTrigger Exact with negative timestamp should accept value`() {
        val trigger = TaskTrigger.Exact(atEpochMillis = -1000)
        assertEquals(-1000L, trigger.atEpochMillis)
    }

    @Test
    fun `TaskTrigger Exact with max long timestamp should accept value`() {
        val trigger = TaskTrigger.Exact(atEpochMillis = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, trigger.atEpochMillis)
    }

    @Test
    fun `TaskTrigger Windowed with earliest greater than latest should accept value`() {
        val trigger = TaskTrigger.Windowed(earliest = 2000, latest = 1000)
        assertEquals(2000L, trigger.earliest)
        assertEquals(1000L, trigger.latest)
    }

    @Test
    fun `TaskTrigger Windowed with equal earliest and latest should accept value`() {
        val trigger = TaskTrigger.Windowed(earliest = 1000, latest = 1000)
        assertEquals(1000L, trigger.earliest)
        assertEquals(1000L, trigger.latest)
    }

    @Test
    fun `TaskTrigger ContentUri with empty string should accept value`() {
        val trigger = TaskTrigger.ContentUri(uriString = "")
        assertEquals("", trigger.uriString)
    }

    @Test
    fun `TaskTrigger ContentUri with very long URI should accept value`() {
        val longUri = "content://media/" + "a".repeat(10000)
        val trigger = TaskTrigger.ContentUri(uriString = longUri)
        assertEquals(longUri, trigger.uriString)
    }

    @Test
    fun `Constraints with zero backoffDelayMs should accept value`() {
        val constraints = Constraints(backoffDelayMs = 0)
        assertEquals(0L, constraints.backoffDelayMs)
    }

    @Test
    fun `Constraints with negative backoffDelayMs should accept value`() {
        val constraints = Constraints(backoffDelayMs = -1000)
        assertEquals(-1000L, constraints.backoffDelayMs)
    }

    @Test
    fun `Constraints with max long backoffDelayMs should accept value`() {
        val constraints = Constraints(backoffDelayMs = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, constraints.backoffDelayMs)
    }

    @Test
    fun `TaskRequest with empty workerClassName should accept value`() {
        val request = TaskRequest(workerClassName = "")
        assertEquals("", request.workerClassName)
    }

    @Test
    fun `TaskRequest with very long workerClassName should accept value`() {
        val longName = "Worker" + "A".repeat(10000)
        val request = TaskRequest(workerClassName = longName)
        assertEquals(longName, request.workerClassName)
    }

    @Test
    fun `TaskRequest with empty inputJson should accept value`() {
        val request = TaskRequest(workerClassName = "Worker", inputJson = "")
        assertEquals("", request.inputJson)
    }

    @Test
    fun `TaskRequest with very long inputJson should accept value`() {
        val longJson = """{"data": "${"x".repeat(10000)}"}"""
        val request = TaskRequest(workerClassName = "Worker", inputJson = longJson)
        assertEquals(longJson, request.inputJson)
    }

    @Test
    fun `TaskChain then with empty list should throw IllegalArgumentException`() {
        val scheduler = MockBackgroundTaskScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        assertFailsWith<IllegalArgumentException> {
            chain.then(emptyList())
        }
    }

    @Test
    fun `TaskChain with single task should have one step with one task`() {
        val scheduler = MockBackgroundTaskScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(1, steps[0].size)
    }

    @Test
    fun `TaskChain with very long chain should handle correctly`() {
        val scheduler = MockBackgroundTaskScheduler()
        var chain = scheduler.beginWith(TaskRequest("Worker0"))

        // Create a chain with 100 sequential tasks
        for (i in 1..100) {
            chain = chain.then(TaskRequest("Worker$i"))
        }

        val steps = chain.getSteps()
        assertEquals(101, steps.size)
        assertEquals("Worker0", steps[0][0].workerClassName)
        assertEquals("Worker100", steps[100][0].workerClassName)
    }

    @Test
    fun `TaskChain with large parallel group should handle correctly`() {
        val scheduler = MockBackgroundTaskScheduler()
        val parallelTasks = (1..100).map { TaskRequest("Worker$it") }
        val chain = scheduler.beginWith(parallelTasks)

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(100, steps[0].size)
    }

    @Test
    fun `TaskCompletionEvent with empty message should accept value`() {
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = true,
            message = ""
        )
        assertEquals("", event.message)
    }

    @Test
    fun `TaskCompletionEvent with very long message should accept value`() {
        val longMessage = "Error: " + "x".repeat(10000)
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = false,
            message = longMessage
        )
        assertEquals(longMessage, event.message)
    }

    @Test
    fun `TaskCompletionEvent with special characters should accept value`() {
        val event = TaskCompletionEvent(
            taskName = "Task 🚀",
            success = true,
            message = "Success! ✅ 日本語 中文"
        )
        assertEquals("Task 🚀", event.taskName)
        assertEquals("Success! ✅ 日本語 中文", event.message)
    }

    // Mock scheduler for testing
    private class MockBackgroundTaskScheduler : BackgroundTaskScheduler {
        override suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy
        ): ScheduleResult = ScheduleResult.ACCEPTED

        override fun cancel(id: String) {}
        override fun cancelAll() {}

        override fun beginWith(task: TaskRequest): TaskChain {
            return TaskChain(this, listOf(task))
        }

        override fun beginWith(tasks: List<TaskRequest>): TaskChain {
            return TaskChain(this, tasks)
        }

        override fun enqueueChain(chain: TaskChain) {}
    }
}
