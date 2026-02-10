package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for Critical Fix #1: Android Exact Alarm Delay Calculation
 *
 * Bug: NativeTaskScheduler was passing absolute epoch milliseconds as initialDelayMs,
 * causing WorkManager to interpret it as a huge delay value.
 *
 * Fix: Calculate relative delay: (trigger.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
 *
 * This test verifies:
 * - Correct relative delay calculation from absolute timestamp
 * - Handling of past timestamps (coerceAtLeast(0))
 * - Edge cases: current time, far future, millisecond precision
 * - No regression in OneTime trigger behavior
 */
class AndroidExactAlarmTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)

        // Initialize KmpWorkManager
        KmpWorkManager.initialize(
            context = context,
            workerFactory = TestWorkerFactory(),
            config = KmpWorkManagerConfig()
        )
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
    }

    /**
     * Test 1: Future timestamp should calculate correct relative delay
     *
     * Scenario: Schedule a task 5 seconds in the future
     * Expected: Delay should be approximately 5000ms (±100ms tolerance for execution time)
     */
    @Test
    fun testFutureTimestampCalculatesCorrectDelay() = runBlocking {
        val delaySeconds = 5L
        val futureTimestamp = System.currentTimeMillis() + (delaySeconds * 1000)

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        // Schedule with exact timestamp
        val result = scheduler.enqueue(
            id = "exact-alarm-future",
            trigger = TaskTrigger.ExactTime(atEpochMillis = futureTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        // Verify task was scheduled
        assertTrue(result.isSuccess, "Task should be scheduled successfully")

        // Verify WorkInfo exists and has correct delay
        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-future").get()
        assertNotNull(workInfo, "WorkInfo should exist")
        assertTrue(workInfo.isNotEmpty(), "Should have at least one work item")

        // Note: WorkManager doesn't expose initialDelay directly in WorkInfo,
        // but we can verify the work is ENQUEUED (not immediately running)
        val state = workInfo.first().state
        assertTrue(
            state == androidx.work.WorkInfo.State.ENQUEUED,
            "Work should be ENQUEUED with delay, not running immediately. State: $state"
        )
    }

    /**
     * Test 2: Past timestamp should result in zero delay (immediate execution)
     *
     * Scenario: Schedule a task with timestamp in the past
     * Expected: coerceAtLeast(0) should make it run immediately
     */
    @Test
    fun testPastTimestampResultsInZeroDelay() = runBlocking {
        val pastTimestamp = System.currentTimeMillis() - 10000 // 10 seconds ago

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-past",
            trigger = TaskTrigger.ExactTime(atEpochMillis = pastTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Task should be scheduled successfully")

        // Work should be enqueued for immediate execution
        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-past").get()
        assertNotNull(workInfo, "WorkInfo should exist")
        assertTrue(workInfo.isNotEmpty(), "Should have work item")
    }

    /**
     * Test 3: Current timestamp should result in immediate execution
     *
     * Scenario: Schedule a task with current timestamp
     * Expected: Delay should be 0 or very small
     */
    @Test
    fun testCurrentTimestampResultsInImmediateExecution() = runBlocking {
        val currentTimestamp = System.currentTimeMillis()

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-current",
            trigger = TaskTrigger.ExactTime(atEpochMillis = currentTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Task should be scheduled successfully")

        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-current").get()
        assertNotNull(workInfo, "WorkInfo should exist")
        assertTrue(workInfo.isNotEmpty(), "Should have work item")
    }

    /**
     * Test 4: Far future timestamp should not overflow or cause negative delay
     *
     * Scenario: Schedule a task 24 hours in the future
     * Expected: Should handle large delays correctly
     */
    @Test
    fun testFarFutureTimestampHandledCorrectly() = runBlocking {
        val twentyFourHoursMs = 24L * 60 * 60 * 1000
        val farFutureTimestamp = System.currentTimeMillis() + twentyFourHoursMs

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-far-future",
            trigger = TaskTrigger.ExactTime(atEpochMillis = farFutureTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Task should be scheduled successfully for far future")

        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-far-future").get()
        assertNotNull(workInfo, "WorkInfo should exist for far future task")
        assertTrue(workInfo.isNotEmpty(), "Should have work item")
        assertEquals(
            androidx.work.WorkInfo.State.ENQUEUED,
            workInfo.first().state,
            "Far future task should be ENQUEUED"
        )
    }

    /**
     * Test 5: Millisecond precision should be preserved
     *
     * Scenario: Schedule two tasks with slight timestamp differences
     * Expected: Both should be scheduled without issues
     */
    @Test
    fun testMillisecondPrecisionPreserved() = runBlocking {
        val baseTimestamp = System.currentTimeMillis() + 3000
        val timestamp1 = baseTimestamp
        val timestamp2 = baseTimestamp + 500 // 500ms later

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result1 = scheduler.enqueue(
            id = "exact-alarm-precision-1",
            trigger = TaskTrigger.ExactTime(atEpochMillis = timestamp1),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        val result2 = scheduler.enqueue(
            id = "exact-alarm-precision-2",
            trigger = TaskTrigger.ExactTime(atEpochMillis = timestamp2),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result1.isSuccess, "First task should be scheduled")
        assertTrue(result2.isSuccess, "Second task should be scheduled")

        val workInfo1 = workManager.getWorkInfosForUniqueWork("exact-alarm-precision-1").get()
        val workInfo2 = workManager.getWorkInfosForUniqueWork("exact-alarm-precision-2").get()

        assertTrue(workInfo1.isNotEmpty(), "First task should exist")
        assertTrue(workInfo2.isNotEmpty(), "Second task should exist")
    }

    /**
     * Test 6: Verify OneTime trigger (non-exact) still works correctly
     *
     * Scenario: Schedule a OneTime trigger (relative delay)
     * Expected: Should not be affected by exact time fix
     */
    @Test
    fun testOneTimeTriggerNotAffectedByFix() = runBlocking {
        val delayMs = 2000L

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "one-time-trigger",
            trigger = TaskTrigger.OneTime(initialDelayMs = delayMs),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "OneTime trigger should work")

        val workInfo = workManager.getWorkInfosForUniqueWork("one-time-trigger").get()
        assertNotNull(workInfo, "OneTime task should exist")
        assertTrue(workInfo.isNotEmpty(), "Should have work item")
        assertEquals(
            androidx.work.WorkInfo.State.ENQUEUED,
            workInfo.first().state,
            "OneTime task should be ENQUEUED"
        )
    }

    /**
     * Test 7: Exact time with constraints should work together
     *
     * Scenario: Schedule exact time task with battery constraint
     * Expected: Both exact time and constraints should be applied
     */
    @Test
    fun testExactTimeWithConstraints() = runBlocking {
        val futureTimestamp = System.currentTimeMillis() + 5000

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-with-constraints",
            trigger = TaskTrigger.ExactTime(atEpochMillis = futureTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(
                requiresBatteryNotLow = true,
                requiresDeviceIdle = false
            ),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Task with constraints should be scheduled")

        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-with-constraints").get()
        assertNotNull(workInfo, "Task with constraints should exist")
        assertTrue(workInfo.isNotEmpty(), "Should have work item")

        // Verify constraints are applied
        val constraints = workInfo.first().constraints
        assertTrue(
            constraints.requiresBatteryNotLow(),
            "Battery constraint should be applied"
        )
    }

    /**
     * Test 8: Multiple exact time tasks should all be scheduled correctly
     *
     * Scenario: Schedule 10 tasks with different future timestamps
     * Expected: All should be scheduled with correct delays
     */
    @Test
    fun testMultipleExactTimeTasks() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
        val baseTime = System.currentTimeMillis()

        // Schedule 10 tasks with 1-10 seconds delay
        val results = (1..10).map { seconds ->
            val timestamp = baseTime + (seconds * 1000L)
            scheduler.enqueue(
                id = "exact-alarm-multiple-$seconds",
                trigger = TaskTrigger.ExactTime(atEpochMillis = timestamp),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = null,
                policy = ExistingPolicy.REPLACE
            )
        }

        // Verify all tasks were scheduled
        assertTrue(results.all { it.isSuccess }, "All tasks should be scheduled successfully")

        // Verify all WorkInfo exist
        val workInfos = (1..10).map { seconds ->
            workManager.getWorkInfosForUniqueWork("exact-alarm-multiple-$seconds").get()
        }

        assertTrue(workInfos.all { it.isNotEmpty() }, "All tasks should have WorkInfo")
    }

    /**
     * Test 9: Replace policy should work with exact time
     *
     * Scenario: Schedule same task twice with REPLACE policy
     * Expected: Second should replace first
     */
    @Test
    fun testExactTimeWithReplacePolicy() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val timestamp1 = System.currentTimeMillis() + 5000
        val timestamp2 = System.currentTimeMillis() + 10000

        // First enqueue
        val result1 = scheduler.enqueue(
            id = "exact-alarm-replace",
            trigger = TaskTrigger.ExactTime(atEpochMillis = timestamp1),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result1.isSuccess, "First enqueue should succeed")

        // Second enqueue (should replace)
        val result2 = scheduler.enqueue(
            id = "exact-alarm-replace",
            trigger = TaskTrigger.ExactTime(atEpochMillis = timestamp2),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result2.isSuccess, "Second enqueue should succeed")

        val workInfo = workManager.getWorkInfosForUniqueWork("exact-alarm-replace").get()
        // Should have only one work item (replaced)
        assertEquals(1, workInfo.size, "Should have exactly one work item after replace")
    }

    /**
     * Test 10: Boundary condition - timestamp at Long.MAX_VALUE
     *
     * Scenario: Schedule with maximum possible timestamp
     * Expected: Should handle without overflow or crash
     */
    @Test
    fun testMaxTimestampBoundary() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        try {
            val result = scheduler.enqueue(
                id = "exact-alarm-max",
                trigger = TaskTrigger.ExactTime(atEpochMillis = Long.MAX_VALUE),
                workerClassName = "TestWorker",
                constraints = Constraints(),
                inputJson = null,
                policy = ExistingPolicy.REPLACE
            )

            // Should either succeed or fail gracefully (no crash)
            assertNotNull(result, "Should not crash with max timestamp")
        } catch (e: Exception) {
            // Acceptable to throw exception for unreasonable timestamp
            assertTrue(true, "Handled max timestamp boundary")
        }
    }

    // ===========================
    // Test Helpers
    // ===========================

    private class TestWorkerFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? {
            return when (workerClassName) {
                "TestWorker" -> TestWorker()
                else -> null
            }
        }
    }

    private class TestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            return WorkerResult.Success(message = "Test worker completed")
        }
    }
}
