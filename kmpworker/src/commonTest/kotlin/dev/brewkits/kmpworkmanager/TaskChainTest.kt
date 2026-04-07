package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskRequestTest {

    @Test
    fun TaskRequest_with_only_workerClassName_should_have_null_input_and_constraints() {
        val request = TaskRequest(workerClassName = "TestWorker")

        assertEquals("TestWorker", request.workerClassName)
        assertEquals(null, request.inputJson)
        assertEquals(null, request.constraints)
    }

    @Test
    fun TaskRequest_with_input_JSON_should_preserve_value() {
        val inputJson = """{"key": "value"}"""
        val request = TaskRequest(
            workerClassName = "DataWorker",
            inputJson = inputJson
        )

        assertEquals("DataWorker", request.workerClassName)
        assertEquals(inputJson, request.inputJson)
    }

    @Test
    fun TaskRequest_with_constraints_should_preserve_constraints() {
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
    fun TaskRequest_with_all_parameters_should_preserve_all_values() {
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

        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
            enqueuedChain = chain
            enqueueCalled = true
        }

        override fun flushPendingProgress() {}

        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()

        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun TaskChain_with_single_initial_task_should_have_one_step() {
        val scheduler = MockScheduler()
        val task = TaskRequest("Worker1")
        val chain = scheduler.beginWith(task)

        val steps = chain.getSteps()
        assertEquals(1, steps.size)
        assertEquals(1, steps[0].size)
        assertEquals("Worker1", steps[0][0].workerClassName)
    }

    @Test
    fun TaskChain_with_multiple_initial_tasks_should_have_one_parallel_step() {
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
    fun TaskChain_then_with_single_task_should_add_sequential_step() {
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
    fun TaskChain_then_with_task_list_should_add_parallel_step() {
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
    fun TaskChain_then_with_empty_list_should_be_ignored() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))

        // This should NOT throw, and the chain size should remain 1
        chain.then(emptyList<TaskRequest>())
        
        val steps = chain.getSteps()
        assertEquals(1, steps.size, "Chain size should remain 1 after then(emptyList())")
    }

    @Test
    fun TaskChain_complex_chain_should_preserve_order() {
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
    fun TaskChain_enqueue_should_call_scheduler_enqueueChain() = runBlocking {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
            .then(TaskRequest("Worker2"))

        chain.enqueue()

        assertTrue(scheduler.enqueueCalled)
        assertEquals(chain, scheduler.enqueuedChain)
    }

    @Test
    fun TaskChain_fluent_API_should_return_same_instance_for_chaining() {
        val scheduler = MockScheduler()
        val chain = scheduler.beginWith(TaskRequest("Worker1"))
        val returnedChain = chain.then(TaskRequest("Worker2"))

        assertEquals(chain, returnedChain)
    }
}
