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
 * Android instrumented tests for v2.4.3 bug fixes.
 * 
 * Verifies that periodic task scheduling correctly handles flexMs and initialDelay
 * based on the runImmediately flag.
 */
@RunWith(AndroidJUnit4::class)
class BugFixes_v243_AndroidTest {

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

    /**
     * Verify that cleanupZombieInputFiles deletes old kmp_input_*.json files.
     */
    @Test
    fun testZombieFileCleanup() = runTest {
        val cacheDir = context.cacheDir
        
        // 1. Create a fresh file (should NOT be deleted)
        val freshFile = java.io.File(cacheDir, "kmp_input_fresh.json")
        freshFile.writeText("{}")
        
        // 2. Create an old file (should BE deleted)
        val oldFile = java.io.File(cacheDir, "kmp_input_old.json")
        oldFile.writeText("{}")
        // Manually set last modified to 48 hours ago
        val oldTimestamp = System.currentTimeMillis() - (48 * 60 * 60 * 1000L)
        oldFile.setLastModified(oldTimestamp)
        
        // 3. Create a non-matching file (should NOT be deleted even if old)
        val nonMatchingFile = java.io.File(cacheDir, "other_file.json")
        nonMatchingFile.writeText("{}")
        nonMatchingFile.setLastModified(oldTimestamp)

        // Reset the singleton guard for testing purposes
        NativeTaskScheduler.resetCleanupStartedForTesting()

        // Run cleanup
        NativeTaskScheduler.cleanupZombieInputFiles(context)
        
        // Wait for CoroutineScope(Dispatchers.IO).launch to complete
        // Since we can't easily await the internal scope, we poll
        var attempts = 0
        while (oldFile.exists() && attempts < 50) {
            kotlinx.coroutines.delay(100)
            attempts++
        }
        
        if (oldFile.exists()) {
            val lastMod = oldFile.lastModified()
            val now = System.currentTimeMillis()
            val age = now - lastMod
            android.util.Log.e("Test", "Old file still exists! Age: ${age}ms, LastMod: $lastMod, Now: $now")
            val cacheFiles = cacheDir.list()?.joinToString()
            android.util.Log.e("Test", "Files in cache: $cacheFiles")
        }

        assertTrue("Fresh file should remain", freshFile.exists())
        assertTrue("Old file should be deleted", !oldFile.exists())
        assertTrue("Non-matching file should remain", nonMatchingFile.exists())
        
        // Clean up
        freshFile.delete()
        nonMatchingFile.delete()
    }
}
