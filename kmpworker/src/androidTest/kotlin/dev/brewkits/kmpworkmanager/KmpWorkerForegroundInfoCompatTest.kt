package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkInfo
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
 * Android instrumented tests for v2.3.3 Fix #1:
 * KmpWorker.getForegroundInfo() override for WorkManager 2.10.0+ compatibility.
 *
 * These tests verify that:
 * 1. Expedited tasks (which trigger getForegroundInfoAsync()) can be scheduled without crash
 * 2. Regular tasks still work as expected
 * 3. Task chains with KmpWorker complete without IllegalStateException
 * 4. Notification resource strings exist and are non-empty
 *
 * Device/emulator required to run these tests.
 */
class KmpWorkerForegroundInfoCompatTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)

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
     * Test 1: Expedited OneTime task must not crash with IllegalStateException.
     *
     * Before Fix #1: KmpWorker did not override getForegroundInfo()
     * → WorkManager 2.10.0+ called getForegroundInfoAsync() on it
     * → Default CoroutineWorker.getForegroundInfo() threw:
     *     IllegalStateException: "Not implemented (CoroutineWorker.kt:92)"
     *
     * After Fix #1: getForegroundInfo() is properly overridden with fallback notification.
     */
    @Test
    fun testExpeditedOneTimeTaskDoesNotCrash() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        // OneTime (non-heavy) tasks use KmpWorker with setExpedited()
        // This is the exact path that triggered the crash on WorkManager 2.10.0+
        val result = scheduler.enqueue(
            id = "compat-expedited-test",
            trigger = TaskTrigger.OneTime(initialDelayMs = 1000),
            workerClassName = "SimpleWorker",
            constraints = Constraints(isHeavyTask = false),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Expedited task should be scheduled without crash")

        val workInfo = workManager.getWorkInfosForUniqueWork("compat-expedited-test").get()
        assertNotNull(workInfo)
        assertTrue(workInfo.isNotEmpty(), "WorkInfo must exist")
        assertTrue(
            workInfo.first().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING),
            "Task must be ENQUEUED or RUNNING, not crashed. State: ${workInfo.first().state}"
        )
    }

    /**
     * Test 2: Multiple expedited tasks can be scheduled concurrently.
     *
     * Verifies that the getForegroundInfo() fix is stable under concurrent task scheduling.
     */
    @Test
    fun testMultipleExpeditedTasksDoNotCrash() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val taskIds = (1..5).map { "compat-concurrent-$it" }
        val results = taskIds.map { id ->
            scheduler.enqueue(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = 2000),
                workerClassName = "SimpleWorker",
                constraints = Constraints(isHeavyTask = false),
                inputJson = """{"taskId":"$id"}""",
                policy = ExistingPolicy.REPLACE
            )
        }

        assertTrue(results.all { it.isSuccess }, "All expedited tasks should schedule without crash")

        taskIds.forEach { id ->
            val workInfo = workManager.getWorkInfosForUniqueWork(id).get()
            assertTrue(workInfo.isNotEmpty(), "WorkInfo must exist for task: $id")
        }
    }

    /**
     * Test 3: Periodic task also uses KmpWorker — must not crash.
     *
     * Periodic tasks always use KmpWorker. The getForegroundInfo() fix covers this path too.
     */
    @Test
    fun testPeriodicTaskWithKmpWorkerDoesNotCrash() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        val result = scheduler.enqueue(
            id = "compat-periodic-test",
            trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000L), // 15 min minimum
            workerClassName = "SimpleWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        assertTrue(result.isSuccess, "Periodic task with KmpWorker should schedule without crash")

        val workInfo = workManager.getWorkInfosForUniqueWork("compat-periodic-test").get()
        assertTrue(workInfo.isNotEmpty(), "Periodic WorkInfo must exist")
    }

    /**
     * Test 4: Notification string resources exist and are non-empty.
     *
     * Verifies the strings.xml resources for KmpWorker notifications are properly defined.
     * If this test fails, it means the resource file was not included in the AAR.
     */
    @Test
    fun testNotificationStringResourcesExist() {
        val channelName = context.getString(dev.brewkits.kmpworkmanager.R.string.kmp_worker_notification_channel_name)
        val title = context.getString(dev.brewkits.kmpworkmanager.R.string.kmp_worker_notification_title)

        assertTrue(channelName.isNotBlank(), "kmp_worker_notification_channel_name must not be blank")
        assertTrue(title.isNotBlank(), "kmp_worker_notification_title must not be blank")
    }

    /**
     * Test 5: KmpHeavyWorker string resources exist.
     */
    @Test
    fun testHeavyWorkerNotificationStringResourcesExist() {
        val channelName = context.getString(dev.brewkits.kmpworkmanager.R.string.kmp_heavy_worker_notification_channel_name)
        val defaultTitle = context.getString(dev.brewkits.kmpworkmanager.R.string.kmp_heavy_worker_notification_default_title)
        val defaultText = context.getString(dev.brewkits.kmpworkmanager.R.string.kmp_heavy_worker_notification_default_text)

        assertTrue(channelName.isNotBlank(), "kmp_heavy_worker_notification_channel_name must not be blank")
        assertTrue(defaultTitle.isNotBlank(), "kmp_heavy_worker_notification_default_title must not be blank")
        assertTrue(defaultText.isNotBlank(), "kmp_heavy_worker_notification_default_text must not be blank")
    }

    /**
     * Test 6: Chain with heavy task is now correctly routed to KmpHeavyWorker (Fix #2).
     *
     * Verifies the chain scheduling path reaches enqueueChain() without error.
     * Actual worker routing to KmpHeavyWorker is verified in KmpHeavyWorkerUsageTest.
     */
    @Test
    fun testChainWithHeavyTaskSchedulesWithoutError() = runBlocking {
        val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
        val chain = scheduler.beginWith(
            dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                workerClassName = "SimpleWorker",
                constraints = Constraints(isHeavyTask = false)
            )
        ).then(
            dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                workerClassName = "SimpleWorker",
                constraints = Constraints(isHeavyTask = true)
            )
        )

        // Should not throw
        chain.enqueue()

        // Give WorkManager a moment to register
        Thread.sleep(500)

        // Verify the chain was accepted by WorkManager
        val allWork = workManager.getWorkInfosByTag("KMP_TASK").get()
        assertTrue(allWork.isNotEmpty(), "Chain tasks should be registered with WorkManager")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private class TestWorkerFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? {
            return when (workerClassName) {
                "SimpleWorker" -> SimpleWorker()
                else -> null
            }
        }
    }

    private class SimpleWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            return WorkerResult.Success(message = "SimpleWorker completed")
        }
    }
}
