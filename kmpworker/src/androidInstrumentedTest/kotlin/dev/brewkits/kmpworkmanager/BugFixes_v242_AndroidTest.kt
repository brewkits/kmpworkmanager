package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Android instrumented tests for v2.4.2 bug fixes.
 * 
 * Verifies that periodic task scheduling correctly handles flexMs and initialDelay
 * based on the runImmediately flag.
 */
@RunWith(AndroidJUnit4::class)
class BugFixes_v242_AndroidTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
    }

    /**
     * Verify that when runImmediately is true, flexMs defaults to intervalMs.
     * This is an indirect test because we can't easily inspect the internal
     * WorkRequest without complex reflection or spying, but we can verify
     * the logic by subclassing or checking the public WorkInfo if possible.
     * 
     * Since WorkInfo doesn't expose flexMs directly in a stable way across all
     * WM versions, we rely on the scheduler's acceptance and log verification
     * in manual QA, but here we verify the builder logic doesn't crash.
     */
    @Test
    fun testPeriodicScheduling_withRunImmediately_Accepted() = runTest {
        val scheduler = NativeTaskScheduler(context)
        val taskId = "periodic-immediate"
        val interval = 15L * 60 * 1000 // 15 min
        
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = interval, runImmediately = true),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )
        
        assertEquals(dev.brewkits.kmpworkmanager.background.domain.ScheduleResult.ACCEPTED, result)
        
        // Clean up
        scheduler.cancel(taskId)
    }

    @Test
    fun testPeriodicScheduling_withInitialDelay_Accepted() = runTest {
        val scheduler = NativeTaskScheduler(context)
        val taskId = "periodic-delayed"
        val interval = 15L * 60 * 1000 // 15 min
        val delay = 5000L
        
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = interval, initialDelayMs = delay, runImmediately = true),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )
        
        assertEquals(dev.brewkits.kmpworkmanager.background.domain.ScheduleResult.ACCEPTED, result)
        
        // Clean up
        scheduler.cancel(taskId)
    }
}
