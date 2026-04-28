package dev.brewkits.kmpworkmanager.background.domain

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class TaskChainLogicTest {

    private class SimpleMockScheduler : BackgroundTaskScheduler {
        val enqueuedChains = mutableListOf<Pair<TaskChain, String?>>()
        
        override suspend fun enqueue(id: String, trigger: TaskTrigger, workerClassName: String, constraints: Constraints, inputJson: String?, policy: ExistingPolicy) = ScheduleResult.ACCEPTED
        override fun cancel(id: String) {}
        override fun cancelAll() {}
        override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
            enqueuedChains.add(chain to id)
        }
        override fun flushPendingProgress() {}
        override suspend fun getExecutionHistory(limit: Int) = emptyList<ExecutionRecord>()
        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun testComplexChainConstruction() = runTest {
        val scheduler = SimpleMockScheduler()
        val request1 = TaskRequest("Worker1")
        val request2 = TaskRequest("Worker2")
        val request3 = TaskRequest("Worker3")

        val chain = scheduler.beginWith(request1)
            .then(listOf(request2, request3))
        
        val steps = chain.getSteps()
        assertEquals(2, steps.size)
        assertEquals(1, steps[0].size)
        assertEquals(2, steps[1].size)
        
        chain.enqueue(id = "complex-chain")
        assertEquals(1, scheduler.enqueuedChains.size)
        assertEquals("complex-chain", scheduler.enqueuedChains[0].second)
    }

    @Test
    fun testRetryPolicyLogic() {
        val linear = Constraints(backoffPolicy = BackoffPolicy.LINEAR, backoffDelayMs = 1000)
        assertEquals(BackoffPolicy.LINEAR, linear.backoffPolicy)
        assertEquals(1000L, linear.backoffDelayMs)

        val exponential = Constraints(backoffPolicy = BackoffPolicy.EXPONENTIAL, backoffDelayMs = 2000)
        assertEquals(BackoffPolicy.EXPONENTIAL, exponential.backoffPolicy)
        
        // Test TaskPriority branches
        val high = TaskRequest("W", priority = TaskPriority.HIGH)
        assertEquals(TaskPriority.HIGH, high.priority)
    }
}
