package dev.brewkits.kmpworkmanager.background.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Regression net for two bugs fixed in [AndroidWorkerDiagnostics]:
 *
 * 1. `getSchedulerStatus()` queried `WorkManager.getWorkInfosByTag("KMP_TASK")` — a
 *    literal that never matched the actual tag `NativeTaskScheduler` stamps
 *    ([NativeTaskScheduler.TAG_KMP_TASK] = `"kmp-worker-task"`) — so this always
 *    reported zero pending tasks regardless of what was actually enqueued.
 * 2. `getTaskStatus(id)` matched worker tags with the prefix `"worker:"` (colon)
 *    while `NativeTaskScheduler` stamps `"worker-<className>"` (hyphen), so
 *    `workerClassName` always fell back to `"Unknown"`.
 *
 * Both used the blocking `Future.get()` instead of `.await()` as well; that isn't
 * separately observable from a Robolectric unit test (both resolve synchronously
 * under `SynchronousExecutor`), so the tag-matching correctness is what's pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidWorkerDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler
    private lateinit var diagnostics: AndroidWorkerDiagnostics

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
        diagnostics = AndroidWorkerDiagnostics(context)
    }

    @Test
    fun getSchedulerStatus_reportsTasksActuallyEnqueuedByNativeTaskScheduler() = runTest {
        scheduler.enqueue(
            id = "diag-task-1",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        scheduler.enqueue(
            id = "diag-task-2",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val status = diagnostics.getSchedulerStatus()

        assertEquals(2, status.queueSize, "must actually find the enqueued WorkRequests, not report a stale-tag 0")
    }

    @Test
    fun getSchedulerStatus_withNoTasks_reportsZero() = runTest {
        val status = diagnostics.getSchedulerStatus()
        assertEquals(0, status.queueSize)
        assertEquals(0, status.totalPendingTasks)
    }

    @Test
    fun getTaskStatus_resolvesTheRealWorkerClassName_notUnknown() = runTest {
        scheduler.enqueue(
            id = "diag-named-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val detail = diagnostics.getTaskStatus("diag-named-task")

        assertNotNull(detail, "task must be found by its unique work name")
        assertEquals(
            "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
            detail.workerClassName,
            "a stale 'worker:' prefix previously left this permanently 'Unknown'"
        )
    }

    @Test
    fun getTaskStatus_forUnknownId_returnsNull() = runTest {
        assertNull(diagnostics.getTaskStatus("no-such-task"))
    }
}
