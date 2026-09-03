package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.testing.FakeBackgroundTaskScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackgroundTaskSchedulerExtTest {

    @Test
    fun enqueueOneTime_buildsOneTimeTriggerWithGivenDelay() = runTest {
        val scheduler = FakeBackgroundTaskScheduler()

        scheduler.enqueueOneTime(
            id = "one-time-1",
            workerClassName = "SomeWorker",
            initialDelayMs = 5_000
        )

        val task = scheduler.enqueuedTasks.single()
        assertEquals("one-time-1", task.id)
        assertEquals("SomeWorker", task.workerClassName)
        val trigger = assertIs<TaskTrigger.OneTime>(task.trigger)
        assertEquals(5_000, trigger.initialDelayMs)
        assertEquals(ExistingPolicy.REPLACE, task.policy)
    }

    @Test
    fun enqueueOneTime_defaultsDelayToZeroAndPolicyToReplace() = runTest {
        val scheduler = FakeBackgroundTaskScheduler()

        scheduler.enqueueOneTime(id = "one-time-2", workerClassName = "SomeWorker")

        val task = scheduler.enqueuedTasks.single()
        val trigger = assertIs<TaskTrigger.OneTime>(task.trigger)
        assertEquals(0, trigger.initialDelayMs)
        assertEquals(ExistingPolicy.REPLACE, task.policy)
    }

    @Test
    fun enqueueOneTime_forwardsConstraintsAndCustomPolicy() = runTest {
        val scheduler = FakeBackgroundTaskScheduler()
        val constraints = Constraints(requiresNetwork = true)

        scheduler.enqueueOneTime(
            id = "one-time-3",
            workerClassName = "SomeWorker",
            constraints = constraints,
            policy = ExistingPolicy.KEEP
        )

        val task = scheduler.enqueuedTasks.single()
        assertEquals(constraints, task.constraints)
        assertEquals(ExistingPolicy.KEEP, task.policy)
    }

    @Test
    fun enqueuePeriodic_buildsPeriodicTriggerWithGivenInterval() = runTest {
        val scheduler = FakeBackgroundTaskScheduler()

        scheduler.enqueuePeriodic(
            id = "periodic-1",
            workerClassName = "SyncWorker",
            intervalMs = 15 * 60_000L
        )

        val task = scheduler.enqueuedTasks.single()
        assertEquals("periodic-1", task.id)
        assertEquals("SyncWorker", task.workerClassName)
        val trigger = assertIs<TaskTrigger.Periodic>(task.trigger)
        assertEquals(15 * 60_000L, trigger.intervalMs)
        // Periodic default policy is KEEP (not REPLACE) — a periodic task's timer shouldn't
        // reset just because the app called enqueuePeriodic again on every launch.
        assertEquals(ExistingPolicy.KEEP, task.policy)
    }

    @Test
    fun enqueuePeriodic_forwardsConstraintsAndCustomPolicy() = runTest {
        val scheduler = FakeBackgroundTaskScheduler()
        val constraints = Constraints(requiresCharging = true)

        scheduler.enqueuePeriodic(
            id = "periodic-2",
            workerClassName = "SyncWorker",
            intervalMs = 30 * 60_000L,
            constraints = constraints,
            policy = ExistingPolicy.UPDATE
        )

        val task = scheduler.enqueuedTasks.single()
        assertEquals(constraints, task.constraints)
        assertEquals(ExistingPolicy.UPDATE, task.policy)
    }
}
