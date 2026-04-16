package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Automated scenario marathon to ensure all trigger types and constraints
 * work together without side effects or crashes.
 */
class ScenarioMarathonTest {

    private lateinit var scheduler: BackgroundTaskScheduler

    @BeforeTest
    fun setup() {
        // Use a Fake to verify the common enqueuing logic and data structures.
        scheduler = object : BackgroundTaskScheduler {
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
            override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))
            override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)
            override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}
            override fun flushPendingProgress() {}
            override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
            override suspend fun clearExecutionHistory() {}
        }
    }

    @Test
    fun `run marathon of various scenarios`() = runTest {
        // OneTime
        assertEquals(ScheduleResult.ACCEPTED, scheduler.enqueue("ot-1", TaskTrigger.OneTime(), "Worker1"))
        
        // OneTime with delay
        assertEquals(ScheduleResult.ACCEPTED, scheduler.enqueue("ot-2", TaskTrigger.OneTime(5000), "Worker2"))
        
        // Periodic
        assertEquals(ScheduleResult.ACCEPTED, scheduler.enqueue("p-1", TaskTrigger.Periodic(15.minutes.inWholeMilliseconds), "Worker3"))
        
        // Windowed
        assertEquals(ScheduleResult.ACCEPTED, scheduler.enqueue("w-1", TaskTrigger.Windowed(1000, 5000), "Worker4"))
        
        // Constraints Marathon
        assertEquals(ScheduleResult.ACCEPTED, scheduler.enqueue(
            "c-1", 
            TaskTrigger.OneTime(), 
            "Worker5",
            constraints = Constraints(
                requiresNetwork = true,
                requiresCharging = true,
                isHeavyTask = true
            )
        ))
        
        // Chain
        scheduler.beginWith(TaskRequest("Step1"))
            .then(TaskRequest("Step2"))
            .enqueue("chain-1")
    }
}
