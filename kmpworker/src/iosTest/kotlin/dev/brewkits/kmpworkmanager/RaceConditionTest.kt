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
        // backgroundScope is a real multi-threaded dispatcher, not runTest's virtual one — left
        // open, it can outlive this test method and corrupt the next test's XML reporting.
        val scheduler = NativeTaskScheduler()
        try {
            // 10 iterations x 5 concurrent racers on a FRESH id each time (never a
            // pre-existing one) — regression guard for #98's schedulingMutex fix. A fresh id
            // per iteration deliberately avoids exercising the KEEP-policy "existing metadata"
            // branch, whose isTaskPending() staleness check always reports false in
            // isTestMode (nothing is ever really submitted to BGTaskScheduler for a dynamic
            // task id — see NativeTaskScheduler.kt's submitTaskRequest), making that branch
            // untestable for this exact race scenario and irrelevant to the fix being guarded
            // here (the brand-new-id TOCTOU, not KEEP's existing-metadata semantics).
            repeat(10) { iteration ->
                val taskId = "race-test-task-$iteration"
                val workerClassName = "TestWorker"

                // Send multiple concurrent requests with KEEP policy.
                // If a race condition occurs, multiple jobs might see metadata as null and attempt to save it simultaneously.
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

                // Verify data integrity is maintained — no corrupted/lost write from concurrent access.
                val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
                assertNotNull(metadata, "Metadata should exist after concurrent KEEP enqueues (iteration $iteration)")
                assertEquals(workerClassName, metadata["workerClassName"], "Worker class name must be preserved correctly (iteration $iteration)")
                assertEquals("", metadata["inputJson"], "inputJson must be written (empty string for null input) (iteration $iteration)")
            }
        } finally {
            scheduler.close()
        }
    }
}
