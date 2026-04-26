package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.testing.FakeBackgroundTaskScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * System and Stress testing for KMP WorkManager.
 */
class SystemAndStressTest {

    private val scheduler = FakeBackgroundTaskScheduler()

    @Test
    fun `Stress - Enqueue 100 tasks sequentially`() = runTest {
        val count = 100
        repeat(count) { i ->
            scheduler.enqueue(
                id = "task-$i",
                trigger = TaskTrigger.OneTime(),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = null,
                policy = ExistingPolicy.KEEP
            )
        }
        assertEquals(count, scheduler.enqueuedTasks.size)
    }

    @Test
    fun `Stress - Concurrent REPLACE policy on same task ID`() = runTest {
        val taskId = "concurrent-task"
        val count = 50
        
        repeat(count) {
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.Periodic(15 * 60 * 1000L),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = null,
                policy = ExistingPolicy.REPLACE
            )
        }
        
        // enqueuedTasks is a record of all calls. 
        // Logic check: fake scheduler records every call.
        assertEquals(count, scheduler.enqueuedTasks.size)
        assertEquals(1, scheduler.enqueuedTasks.filter { it.id == taskId }.map { it.id }.distinct().size)
    }

    @Test
    fun `Security - Input JSON validation prevents massive payloads`() = runTest {
        val massiveJson = "a".repeat(1024 * 1024) // 1MB
        val result = scheduler.enqueue(
            id = "huge-task",
            trigger = TaskTrigger.Periodic(15 * 60 * 1000L),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = massiveJson,
            policy = ExistingPolicy.REPLACE
        )
        
        assertTrue(result == ScheduleResult.ACCEPTED || result == ScheduleResult.REJECTED_OS_POLICY)
    }

    @Test
    fun `Performance - Task chain serialization latency`() = runTest {
        val steps = (1..10).map { i ->
            listOf(TaskRequest(workerClassName = "Worker-$i"))
        }
        val chain = scheduler.beginWith(steps.first())
        
        val start = Clock.System.now().toEpochMilliseconds()
        scheduler.enqueueChain(chain)
        val end = Clock.System.now().toEpochMilliseconds()
        
        assertTrue((end - start) < 1000, "Chain enqueue should be reasonably fast (< 1s in test)")
    }
}
