package dev.brewkits.kmpworkmanager.background.domain

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DomainExtTest {

    private class MockScheduler : BackgroundTaskScheduler {
        var lastWorkerClassName: String? = null
        var lastTrigger: TaskTrigger? = null
        var lastId: String? = null
        var lastPolicy: ExistingPolicy? = null

        override suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy
        ): ScheduleResult {
            lastId = id
            lastPolicy = policy
            lastTrigger = trigger
            lastWorkerClassName = workerClassName
            return ScheduleResult.ACCEPTED
        }

        override fun cancel(id: String) {}
        override fun cancelAll() {}
        override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}
        override fun flushPendingProgress() {}
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun testEnqueueOneTimeExtension() = runTest {
        val scheduler = MockScheduler()
        val result = scheduler.enqueueOneTime(
            id = "test-id",
            workerClassName = "TestWorker",
            initialDelayMs = 5000,
            policy = ExistingPolicy.KEEP
        )

        assertEquals(ScheduleResult.ACCEPTED, result)
        assertEquals("test-id", scheduler.lastId)
        assertEquals(ExistingPolicy.KEEP, scheduler.lastPolicy)
        assertEquals("TestWorker", scheduler.lastWorkerClassName)
        assertTrue(scheduler.lastTrigger is TaskTrigger.OneTime)
        assertEquals(5000L, (scheduler.lastTrigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun testEnqueuePeriodicExtension() = runTest {
        val scheduler = MockScheduler()
        val result = scheduler.enqueuePeriodic(
            id = "periodic-id",
            workerClassName = "PeriodicWorker",
            intervalMs = 3600_000
        )

        assertEquals(ScheduleResult.ACCEPTED, result)
        assertEquals("periodic-id", scheduler.lastId)
        assertTrue(scheduler.lastTrigger is TaskTrigger.Periodic)
        assertEquals(3600_000L, (scheduler.lastTrigger as TaskTrigger.Periodic).intervalMs)
    }

    @Test
    fun testTaskTriggerHelpers() {
        val oneTime = createTaskTriggerOneTime(1000L)
        assertTrue(oneTime is TaskTrigger.OneTime)
        assertEquals(1000L, oneTime.initialDelayMs)

        val oneTimeSec = createTaskTriggerOneTimeSeconds(1.5)
        assertTrue(oneTimeSec is TaskTrigger.OneTime)
        assertEquals(1500L, oneTimeSec.initialDelayMs)

        val periodic = createTaskTriggerPeriodic(3600_000L, 300_000L, 0L, false)
        assertTrue(periodic is TaskTrigger.Periodic)
        assertEquals(3600_000L, periodic.intervalMs)
        assertEquals(300_000L, periodic.flexMs)
        assertEquals(0L, periodic.initialDelayMs)
        assertFalse(periodic.runImmediately)

        val periodicSec = createTaskTriggerPeriodicSeconds(3600.0, 60.0, true)
        assertTrue(periodicSec is TaskTrigger.Periodic)
        assertEquals(3600_000L, periodicSec.intervalMs)
        assertEquals(60_000L, periodicSec.initialDelayMs)
        assertTrue(periodicSec.runImmediately)

        val constraints = createConstraints()
        assertFalse(constraints.requiresNetwork)
        assertEquals(Qos.Background, constraints.qos)
    }

    @Test
    fun testDelegatingWorkerFactory() {
        val factory1 = object : WorkerFactory {
            override fun createWorker(workerClassName: String): Worker? =
                if (workerClassName == "Worker1") object : Worker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = WorkerResult.Success()
                } else null
        }

        val factory2 = object : WorkerFactory {
            override fun createWorker(workerClassName: String): Worker? =
                if (workerClassName == "Worker2") object : Worker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = WorkerResult.Success()
                } else null
        }

        val delegatingFactory = DelegatingWorkerFactory(listOf(factory1, factory2))

        assertNotNull(delegatingFactory.createWorker("Worker1"))
        assertNotNull(delegatingFactory.createWorker("Worker2"))
        assertNull(delegatingFactory.createWorker("Worker3"))
    }
}
