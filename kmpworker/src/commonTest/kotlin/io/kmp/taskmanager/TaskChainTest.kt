package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskRequestTest {

    @Test
    fun `TaskRequest with only workerClassName should have null input and constraints`() {
        val request = TaskRequest(workerClassName = "TestWorker")

        assertEquals("TestWorker", request.workerClassName)
        assertEquals(null, request.inputJson)
        assertEquals(null, request.constraints)
    }

    @Test
    fun `TaskRequest with input JSON should preserve value`() {
        val inputJson = """{"key": "value"}"""
        val request = TaskRequest(
            workerClassName = "DataWorker",
            inputJson = inputJson
        )

        assertEquals("DataWorker", request.workerClassName)
        assertEquals(inputJson, request.inputJson)
    }

    @Test
    fun `TaskRequest with constraints should preserve constraints`() {
        val constraints = Constraints(requiresNetwork = true, requiresCharging = true)
        val request = TaskRequest(
            workerClassName = "NetworkWorker",
            constraints = constraints
        )

        assertEquals("NetworkWorker", request.workerClassName)
        assertEquals(constraints, request.constraints)
        assertTrue(request.constraints?.requiresNetwork == true)
        assertTrue(request.constraints?.requiresCharging == true)
    }

    @Test
    fun `TaskRequest with all parameters should preserve all values`() {
        val inputJson = """{"userId": 123}"""
        val constraints = Constraints(requiresNetwork = true)
        val request = TaskRequest(
            workerClassName = "SyncWorker",
            inputJson = inputJson,
            constraints = constraints
        )

        assertEquals("SyncWorker", request.workerClassName)
        assertEquals(inputJson, request.inputJson)
        assertEquals(constraints, request.constraints)
    }
}

class TaskChainTest {

    private class MockScheduler : BackgroundTaskScheduler {
        var enqueuedChain: TaskChain? = null
        var enqueueCalled = false

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

        override fun enqueueChain(chain: TaskChain) {
            enqueuedChain = chain
            enqueueCalled = true
        }
    }

    @Test
    fun `TaskChain with single initial task should have one step`() {
        val scheduler = MockScheduler()
        val task = TaskRequest("Worker1")
        val chain = scheduler.beginWith(task)

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(1, steps[0].size)
        assertEquals("Worker1", steps[0][0].workerClassName)
    }

    @Test
    fun `TaskChain with multiple initial tasks should have one parallel step`() {
        val scheduler = MockScheduler()
        val tasks = listOf(
            TaskRequest("Worker1"),
            TaskRequest("Worker2"),
            TaskRequest("Worker3")
        )
        val chain = scheduler.beginWith(tasks)

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(3, steps[0].size)
        assertEquals("Worker1", steps[0][0].workerClassName)
        assertEquals("Worker2", steps[0][1].workerClassName)
        assertEquals("Worker3", steps[0][2].workerClassName)
    }

    @Test
    fun `TaskChain then with single task should add sequential step`() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
            .then(TaskRequest("Worker2"))
            .then(TaskRequest("Worker3"))

        val steps = chain.getSteps()
        assertEquals(3, steps.size)
        assertEquals(1, steps[0].size)
        assertEquals(1, steps[1].size)
        assertEquals(1, steps[2].size)
        assertEquals("Worker1", steps[0][0].workerClassName)
        assertEquals("Worker2", steps[1][0].workerClassName)
        assertEquals("Worker3", steps[2][0].workerClassName)
    }

    @Test
    fun `TaskChain then with task list should add parallel step`() {
        val scheduler = MockScheduler()
        val parallelTasks = listOf(
            TaskRequest("Worker2"),
            TaskRequest("Worker3")
        )
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
            .then(parallelTasks)

        val steps = chain.getSteps()
        assertEquals(2, steps.size)
        assertEquals(1, steps[0].size)
        assertEquals(2, steps[1].size)
        assertEquals("Worker1", steps[0][0].workerClassName)
        assertEquals("Worker2", steps[1][0].workerClassName)
        assertEquals("Worker3", steps[1][1].workerClassName)
    }

    @Test
    fun `TaskChain with empty task list should throw IllegalArgumentException`() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        assertFailsWith<IllegalArgumentException> {
            chain.then(emptyList())
        }
    }

    @Test
    fun `TaskChain complex chain should preserve order`() {
        val scheduler = MockScheduler()
        // (A) -> (B, C) -> (D) -> (E, F, G)
        val chain = scheduler.beginWith(TaskRequest("A"))
            .then(listOf(TaskRequest("B"), TaskRequest("C")))
            .then(TaskRequest("D"))
            .then(listOf(TaskRequest("E"), TaskRequest("F"), TaskRequest("G")))

        val steps = chain.getSteps()
        assertEquals(4, steps.size)

        // Step 0: A
        assertEquals(1, steps[0].size)
        assertEquals("A", steps[0][0].workerClassName)

        // Step 1: B, C
        assertEquals(2, steps[1].size)
        assertEquals("B", steps[1][0].workerClassName)
        assertEquals("C", steps[1][1].workerClassName)

        // Step 2: D
        assertEquals(1, steps[2].size)
        assertEquals("D", steps[2][0].workerClassName)

        // Step 3: E, F, G
        assertEquals(3, steps[3].size)
        assertEquals("E", steps[3][0].workerClassName)
        assertEquals("F", steps[3][1].workerClassName)
        assertEquals("G", steps[3][2].workerClassName)
    }

    @Test
    fun `TaskChain enqueue should call scheduler enqueueChain`() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
            .then(TaskRequest("Worker2"))

        chain.enqueue()

        assertTrue(scheduler.enqueueCalled)
        assertEquals(chain, scheduler.enqueuedChain)
    }

    @Test
    fun `TaskChain fluent API should return same instance for chaining`() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
        val returnedChain = chain.then(TaskRequest("Worker2"))

        assertEquals(chain, returnedChain)
    }
}
