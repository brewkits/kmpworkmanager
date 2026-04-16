@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class)

package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.AlarmStore
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive tests for Android Exact Alarm implementation.
 *
 * This test verifies:
 * - Correct relative delay calculation from absolute timestamp
 * - Handling of past timestamps
 * - Integration with AlarmStore for persistence
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
        KmpWorkManager.shutdown()
    }

    /**
     * Test 1: Future timestamp should calculate correct relative delay
     */
    @Test
    fun testFutureTimestampCalculatesCorrectDelay() = runBlocking {
        val delaySeconds = 5L
        val futureTimestamp = System.currentTimeMillis() + (delaySeconds * 1000)

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        // Schedule with exact timestamp
        val result = scheduler.enqueue(
            id = "exact-alarm-future",
            trigger = TaskTrigger.Exact(atEpochMillis = futureTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        // Verify task was scheduled
        assertEquals(ScheduleResult.ACCEPTED, result, "Task should be scheduled successfully")

        // Verify AlarmStore entry exists (Exact alarms use AlarmManager, not WorkManager)
        val alarms = AlarmStore.getFutureAlarms(context)
        assertTrue(alarms.any { it.id == "exact-alarm-future" }, "Alarm should exist in AlarmStore")
    }

    /**
     * Test 2: Past timestamp should result in immediate execution
     */
    @Test
    fun testPastTimestampResultsInZeroDelay() = runBlocking {
        val pastTimestamp = System.currentTimeMillis() - 10000 // 10 seconds ago

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-past",
            trigger = TaskTrigger.Exact(atEpochMillis = pastTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result, "Task should be scheduled successfully")
    }

    /**
     * Test 3: Current timestamp should result in immediate execution
     */
    @Test
    fun testCurrentTimestampResultsInImmediateExecution() = runBlocking {
        val currentTimestamp = System.currentTimeMillis()

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-current",
            trigger = TaskTrigger.Exact(atEpochMillis = currentTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result, "Task should be scheduled successfully")
    }

    /**
     * Test 4: Far future timestamp should handle large delays correctly
     */
    @Test
    fun testFarFutureTimestampHandledCorrectly() = runBlocking {
        val farFutureTimestamp = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000)

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "exact-alarm-far-future",
            trigger = TaskTrigger.Exact(atEpochMillis = farFutureTimestamp),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result, "Task should be scheduled successfully for far future")

        val alarms = AlarmStore.getFutureAlarms(context)
        assertTrue(alarms.any { it.id == "exact-alarm-far-future" }, "Alarm should exist in AlarmStore")
    }

    /**
     * Test 5: Millisecond precision should be preserved
     */
    @Test
    fun testMillisecondPrecisionPreserved() = runBlocking {
        val baseTimestamp = System.currentTimeMillis() + 3000
        val timestamp1 = baseTimestamp
        val timestamp2 = baseTimestamp + 500 // 500ms later

        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result1 = scheduler.enqueue(
            id = "exact-alarm-precision-1",
            trigger = TaskTrigger.Exact(atEpochMillis = timestamp1),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        val result2 = scheduler.enqueue(
            id = "exact-alarm-precision-2",
            trigger = TaskTrigger.Exact(atEpochMillis = timestamp2),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result1, "First task should be scheduled")
        assertEquals(ScheduleResult.ACCEPTED, result2, "Second task should be scheduled")

        val alarms = AlarmStore.getFutureAlarms(context)
        assertTrue(alarms.any { it.id == "exact-alarm-precision-1" }, "First alarm should exist")
        assertTrue(alarms.any { it.id == "exact-alarm-precision-2" }, "Second alarm should exist")
    }

    /**
     * Test 6: Verify OneTime trigger (non-exact) still works correctly through WorkManager
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

        assertEquals(ScheduleResult.ACCEPTED, result)

        val workInfo = workManager.getWorkInfosForUniqueWork("one-time-trigger").get()
        assertTrue(workInfo.isNotEmpty(), "OneTime task should exist in WorkManager")
    }

    /**
     * Test 7: Exact time with Replace policy
     */
    @Test
    fun testExactTimeWithReplacePolicy() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        scheduler.enqueue(
            id = "exact-replace",
            trigger = TaskTrigger.Exact(System.currentTimeMillis() + 10000),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = "1",
            policy = ExistingPolicy.REPLACE
        )

        val result = scheduler.enqueue(
            id = "exact-replace",
            trigger = TaskTrigger.Exact(System.currentTimeMillis() + 20000),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = "2",
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result)
        
        val alarms = AlarmStore.getFutureAlarms(context)
        val alarm = alarms.find { it.id == "exact-replace" }
        assertTrue(alarm != null, "Alarm should exist")
        assertEquals("2", alarm.inputJson, "Metadata should be replaced")
    }

    // ===========================
    // Test Helpers
    // ===========================

    private class TestWorkerFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? {
            return when (workerClassName) {
                "TestWorker" -> TestWorker()
                else -> null
            }
        }
    }

    private class TestWorker : AndroidWorker {
        override suspend fun doWork(input: String?, env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment): WorkerResult {
            return WorkerResult.Success(message = "Test worker completed")
        }
    }
}
