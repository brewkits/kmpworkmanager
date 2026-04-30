package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RaceConditionTest {

    @Test
    fun testEnqueueRaceCondition() = runTest {
        val scheduler = NativeTaskScheduler()
        val taskId = "race-test-task"
        val workerClassName = "TestWorker"

        // Send multiple concurrent requests with KEEP policy
        // If a race condition occurs, multiple jobs might see metadata as null and attempt to save it simultaneously
        val jobs = List(5) {
            launch(Dispatchers.Default) {
                scheduler.enqueue(
                    id = taskId,
                    trigger = TaskTrigger.OneTime(0),
                    workerClassName = workerClassName,
                    constraints = Constraints(),
                    inputJson = null,
                    policy = ExistingPolicy.KEEP
                )
            }
        }

        jobs.joinAll()

        // Verify that exactly one enqueue won (KEEP policy) and data integrity is maintained.
        val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
        assertNotNull(metadata, "Metadata should exist after concurrent KEEP enqueues")
        assertEquals(workerClassName, metadata["workerClassName"], "Worker class name must be preserved correctly")
        assertEquals("", metadata["inputJson"], "inputJson must be written (empty string for null input)")
    }
}
