package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.background.domain.AndroidOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EdgeCasesTest {

    @Test
    fun TaskTrigger_OneTime_with_negative_delay_should_accept_value() {
        val trigger = TaskTrigger.OneTime(initialDelayMs = -1000)
        assertEquals(-1000L, trigger.initialDelayMs)
    }

    @Test
    fun TaskTrigger_Periodic_with_zero_interval_should_accept_value() {
        val trigger = TaskTrigger.Periodic(intervalMs = 0)
        assertEquals(0L, trigger.intervalMs)
    }

    @Test
    fun TaskTrigger_Periodic_with_negative_interval_should_accept_value() {
        val trigger = TaskTrigger.Periodic(intervalMs = -1000)
        assertEquals(-1000L, trigger.intervalMs)
    }

    @Test
    fun TaskTrigger_Periodic_with_zero_flex_should_accept_value() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000, flexMs = 0)
        assertEquals(0L, trigger.flexMs)
    }

    @Test
    fun TaskTrigger_Periodic_with_negative_flex_should_accept_value() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000, flexMs = -1000)
        assertEquals(-1000L, trigger.flexMs)
    }

    @Test
    fun TaskTrigger_Exact_with_zero_timestamp_should_accept_value() {
        val trigger = TaskTrigger.Exact(atEpochMillis = 0)
        assertEquals(0L, trigger.atEpochMillis)
    }

    @Test
    fun TaskTrigger_Exact_with_negative_timestamp_should_accept_value() {
        val trigger = TaskTrigger.Exact(atEpochMillis = -1000)
        assertEquals(-1000L, trigger.atEpochMillis)
    }

    @Test
    fun TaskTrigger_Exact_with_max_long_timestamp_should_accept_value() {
        val trigger = TaskTrigger.Exact(atEpochMillis = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, trigger.atEpochMillis)
    }

    @Test
    fun TaskTrigger_Windowed_with_earliest_greater_than_latest_should_accept_value() {
        val trigger = TaskTrigger.Windowed(earliest = 2000, latest = 1000)
        assertEquals(2000L, trigger.earliest)
        assertEquals(1000L, trigger.latest)
    }

    @Test
    fun TaskTrigger_Windowed_with_equal_earliest_and_latest_should_accept_value() {
        val trigger = TaskTrigger.Windowed(earliest = 1000, latest = 1000)
        assertEquals(1000L, trigger.earliest)
        assertEquals(1000L, trigger.latest)
    }

    @OptIn(AndroidOnly::class)
    @Test
    fun TaskTrigger_ContentUri_with_empty_string_should_accept_value() {
        val trigger = TaskTrigger.ContentUri(uriString = "")
        assertEquals("", trigger.uriString)
    }

    @OptIn(AndroidOnly::class)
    @Test
    fun TaskTrigger_ContentUri_with_very_long_URI_should_accept_value() {
        val longUri = "content://media/" + "a".repeat(10000)
        val trigger = TaskTrigger.ContentUri(uriString = longUri)
        assertEquals(longUri, trigger.uriString)
    }

    @Test
    fun Constraints_with_zero_backoffDelayMs_should_accept_value() {
        val constraints = Constraints(backoffDelayMs = 0)
        assertEquals(0L, constraints.backoffDelayMs)
    }

    @Test
    fun Constraints_with_negative_backoffDelayMs_should_accept_value() {
        val constraints = Constraints(backoffDelayMs = -1000)
        assertEquals(-1000L, constraints.backoffDelayMs)
    }

    @Test
    fun Constraints_with_max_long_backoffDelayMs_should_accept_value() {
        val constraints = Constraints(backoffDelayMs = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, constraints.backoffDelayMs)
    }

    @Test
    fun TaskRequest_with_empty_workerClassName_should_accept_value() {
        val request = TaskRequest(workerClassName = "")
        assertEquals("", request.workerClassName)
    }

    @Test
    fun TaskRequest_with_very_long_workerClassName_should_accept_value() {
        val longName = "Worker" + "A".repeat(10000)
        val request = TaskRequest(workerClassName = longName)
        assertEquals(longName, request.workerClassName)
    }

    @Test
    fun TaskRequest_with_empty_inputJson_should_accept_value() {
        val request = TaskRequest(workerClassName = "Worker", inputJson = "")
        assertEquals("", request.inputJson)
    }

    @Test
    fun TaskRequest_with_very_long_inputJson_should_accept_value() {
        val longJson = """{"data": "${"x".repeat(10000)}"}"""
        val request = TaskRequest(workerClassName = "Worker", inputJson = longJson)
        assertEquals(longJson, request.inputJson)
    }

    @Test
    fun TaskChain_then_with_empty_list_should_be_ignored() {
        val scheduler = MockBackgroundTaskScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        // This should NOT throw, and the chain size should remain 1
        chain.then(emptyList())
        
        val steps = chain.getSteps()
        assertEquals(1, steps.size, "Chain size should remain 1 after then(emptyList())")
    }

    @Test
    fun TaskChain_with_single_task_should_have_one_step_with_one_task() {
        val scheduler = MockBackgroundTaskScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(1, steps[0].size)
    }

    @Test
    fun TaskChain_with_very_long_chain_should_handle_correctly() {
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
    fun TaskChain_with_large_parallel_group_should_handle_correctly() {
        val scheduler = MockBackgroundTaskScheduler()
        val parallelTasks = (1..100).map { TaskRequest("Worker$it") }
        val chain = scheduler.beginWith(parallelTasks)

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(100, steps[0].size)
    }

    @Test
    fun TaskCompletionEvent_with_empty_message_should_accept_value() {
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = true,
            message = ""
        )
        assertEquals("", event.message)
    }

    @Test
    fun TaskCompletionEvent_with_very_long_message_should_accept_value() {
        val longMessage = "Error: " + "x".repeat(10000)
        val event = TaskCompletionEvent(
            taskName = "Task",
            success = false,
            message = longMessage
        )
        assertEquals(longMessage, event.message)
    }

    @Test
    fun TaskCompletionEvent_with_special_characters_should_accept_value() {
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

        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}

        override fun flushPendingProgress() {}

        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()

        override suspend fun clearExecutionHistory() {}
    }
}
