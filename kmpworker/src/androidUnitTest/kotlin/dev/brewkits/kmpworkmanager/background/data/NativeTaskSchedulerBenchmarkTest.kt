package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Android-side counterpart to `QueuePerformanceBenchmark` (iOS). Not a 1:1 port: Android
 * has no analogue to iOS's file-backed `AppendOnlyQueue` (enqueue/dequeue ordering here is
 * WorkManager's Room-backed `WorkDatabase`, which this library has no access to benchmark
 * directly) — so this measures the one thing that IS comparable across platforms:
 * `NativeTaskScheduler.enqueue()` latency, via Robolectric's in-memory WorkManager.
 *
 * Like the iOS benchmark, this asserts a loose upper bound (catches a real regression)
 * rather than a tight target — Robolectric's WorkManager is not representative of a real
 * device's WorkDatabase I/O cost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeTaskSchedulerBenchmarkTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun `benchmark enqueue 100 one-time tasks`() = runTest {
        val mark = TimeSource.Monotonic.markNow()
        repeat(100) { i ->
            val result = scheduler.enqueue(
                id = "benchmark-task-$i",
                trigger = TaskTrigger.OneTime(),
                workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
            )
            assertEquals(ScheduleResult.ACCEPTED, result)
        }
        val duration = mark.elapsedNow()
        println("✅ enqueue x100: ${duration.inWholeMilliseconds}ms")
        // Loose bound — Robolectric's in-memory WorkManager, not a real device's
        // WorkDatabase. This catches a gross regression, not a performance target.
        assertEquals(
            true,
            duration.inWholeMilliseconds < 10_000L,
            "Expected <10000ms for 100 enqueues, got ${duration.inWholeMilliseconds}ms"
        )
    }
}
