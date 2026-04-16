package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Stress test for Android implementation.
 * Ensures the system handles rapid high-concurrency scheduling.
 */
@RunWith(AndroidJUnit4::class)
class QA_AndroidStressTest {

    private lateinit var context: Context
    private lateinit var scheduler: BackgroundTaskScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        KmpWorkManager.initialize(
            context = context,
            workerFactory = object : AndroidWorkerFactory {
                override fun createWorker(workerClassName: String): AndroidWorker? = null
            }
        )
        scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
    }

    @After
    fun tearDown() {
        KmpWorkManager.shutdown()
    }

    @Test
    fun test_100_concurrent_enqueues() = runBlocking {
        val count = 100
        val results = (1..count).map { i ->
            async {
                scheduler.enqueue(
                    id = "stress-task-$i",
                    trigger = TaskTrigger.OneTime(),
                    workerClassName = "TestWorker"
                )
            }
        }.awaitAll()

        assertTrue(results.all { it == ScheduleResult.ACCEPTED }, "All 100 tasks should be accepted")
    }

    @Test
    fun test_rapid_reschedule_cycle() = runBlocking {
        val taskId = "rapid-task"
        repeat(50) { i ->
            val result = scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(initialDelayMs = 1000),
                workerClassName = "TestWorker",
                policy = ExistingPolicy.REPLACE
            )
            assertTrue(result == ScheduleResult.ACCEPTED, "Iteration $i failed")
        }
    }
}
