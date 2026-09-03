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
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Benchmark for `NativeTaskScheduler.queryTasks` (new in 3.4.0) — previously unbenchmarked.
 * `queryTasks` does one `getWorkInfosByTag(TAG_KMP_TASK)` fetch covering every task this
 * library has ever created, then filters in-memory (see its KDoc) — the fetch is O(N) in
 * total persisted WorkInfo count, not in the query's own filter selectivity, so a caller
 * with a large task history pays the same fetch cost regardless of how narrow their filter
 * is. This benchmark seeds a few hundred tasks first to measure that realistic cost, next to
 * [NativeTaskSchedulerBenchmarkTest]'s enqueue-latency benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QueryTasksBenchmarkTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun `benchmark queryTasks against 300 previously enqueued tasks`() = runTest {
        repeat(300) { i ->
            val result = scheduler.enqueue(
                id = "query-benchmark-task-$i",
                trigger = TaskTrigger.OneTime(),
                workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
                tags = setOf("benchmark-batch")
            )
            assertEquals(ScheduleResult.ACCEPTED, result)
        }

        val mark = TimeSource.Monotonic.markNow()
        val results = scheduler.queryTasks(tags = setOf("benchmark-batch"))
        val duration = mark.elapsedNow()

        assertEquals(300, results.size, "expected queryTasks to find all 300 seeded tasks")
        println("✅ queryTasks over 300 tasks: ${duration.inWholeMilliseconds}ms")
        // Loose bound, same rationale as NativeTaskSchedulerBenchmarkTest — Robolectric's
        // in-memory WorkManager isn't representative of a real device's WorkDatabase I/O
        // cost. This catches a gross regression (e.g. an accidental N+1 query), not a
        // performance target.
        assertTrue(
            duration.inWholeMilliseconds < 10_000L,
            "Expected <10000ms for queryTasks over 300 tasks, got ${duration.inWholeMilliseconds}ms"
        )
    }
}
