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

    /**
     * Regression guard for the schedulingMutex fix (#98): concurrent `enqueue()` calls using
     * REPLACE policy on a brand-new id must not corrupt metadata, even though REPLACE's
     * `handleExistingPolicy` branch calls `cancel(id)` internally (see
     * `NativeTaskScheduler.handleExistingPolicy`'s KDoc) — that call happens from inside the
     * same coroutine that already holds `schedulingMutex`, so it must not deadlock either.
     */
    @Test
    fun testConcurrentEnqueueReplacePolicyDoesNotCorruptMetadata() = runTest {
        val scheduler = NativeTaskScheduler()
        try {
            repeat(5) { iteration ->
                val taskId = "race-replace-task-$iteration"
                val workerClassName = "TestWorker"

                val jobs = List(5) {
                    launch(Dispatchers.Default) {
                        scheduler.enqueue(
                            id = taskId,
                            trigger = TaskTrigger.OneTime(0),
                            workerClassName = workerClassName,
                            constraints = Constraints(),
                            inputJson = null,
                            policy = ExistingPolicy.REPLACE
                        )
                    }
                }
                jobs.joinAll()

                val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
                assertNotNull(metadata, "Metadata should exist after concurrent REPLACE enqueues (iteration $iteration)")
                assertEquals(workerClassName, metadata["workerClassName"], "Worker class name must be preserved correctly (iteration $iteration)")
            }
        } finally {
            scheduler.close()
        }
    }

    /**
     * Documents (does not "fix") a known, narrower limitation of the schedulingMutex fix
     * (#98): [NativeTaskScheduler.cancel] is a non-suspend `BackgroundTaskScheduler`
     * interface method, so it cannot acquire `schedulingMutex` — a concurrent `enqueue()`
     * (holding the mutex) and `cancel()` (not holding it) for the same id are NOT mutually
     * exclusive. This is called out explicitly in `schedulingMutex`'s own KDoc as a
     * pre-existing, narrower risk class deliberately not bundled into the #98 fix.
     *
     * This test does not assert a specific winner (neither ordering is a bug) — it asserts
     * the property that actually matters: the race must never crash, throw, or leave
     * `loadTaskMetadata` returning corrupt/partial data. Two clean outcomes are acceptable:
     * either the metadata is fully absent (cancel ran after enqueue's write) or fully present
     * and correct (cancel ran before enqueue's write, or missed entirely because it raced
     * before the id existed).
     */
    @Test
    fun testConcurrentEnqueueAndCancelDoesNotCorruptMetadata() = runTest {
        val scheduler = NativeTaskScheduler()
        try {
            repeat(10) { iteration ->
                val taskId = "race-cancel-task-$iteration"
                val workerClassName = "TestWorker"

                val enqueueJob = launch(Dispatchers.Default) {
                    scheduler.enqueue(
                        id = taskId,
                        trigger = TaskTrigger.OneTime(0),
                        workerClassName = workerClassName,
                        constraints = Constraints(),
                        inputJson = null,
                        policy = ExistingPolicy.KEEP
                    )
                }
                val cancelJob = launch(Dispatchers.Default) {
                    scheduler.cancel(taskId)
                }
                joinAll(enqueueJob, cancelJob)

                // Either outcome is acceptable — what matters is that reading it back never
                // throws and, when present, is never partially-written garbage.
                val metadata = scheduler.fileStorage.loadTaskMetadata(taskId, periodic = false)
                if (metadata != null) {
                    assertEquals(workerClassName, metadata["workerClassName"], "If metadata survived the race, it must be complete and correct (iteration $iteration)")
                }
            }
        } finally {
            scheduler.close()
        }
    }
}
