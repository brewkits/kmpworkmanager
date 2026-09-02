package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.TaskState
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Covers `NativeTaskScheduler.observeTaskState` (new in 3.4.0) — the Android side wraps
 * `WorkManager.getWorkInfosForUniqueWorkFlow(id)` directly, so this is mostly a mapping/
 * plumbing test rather than a deep integration one: WorkManager's own test suite already
 * covers `WorkInfo` state transitions exhaustively.
 *
 * Naming convention: `VXYZ...Test` per CLAUDE.md — 3.4.0's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V340ObserveTaskStateAndroidTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun `an id that was never enqueued reports Unknown`() = runTest {
        val state = scheduler.observeTaskState("never-scheduled-${kotlin.random.Random.nextInt()}").first()
        assertEquals(TaskState.Unknown, state)
    }

    @Test
    fun `a freshly enqueued task reports a tracked non-Unknown state`() = runTest {
        val id = "observe-test-${kotlin.random.Random.nextInt()}"
        scheduler.enqueue(
            id = id,
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val state = scheduler.observeTaskState(id).first()
        assertNotEquals(
            TaskState.Unknown, state,
            "WorkManager must have a WorkInfo for a unique work name right after enqueueUniqueWork " +
                "— Unknown here means observeTaskState isn't finding it at all"
        )
    }

    // ==================== WorkInfo.State -> TaskState mapping ====================
    // Exhaustive over every WorkInfo.State value so a future androidx.work upgrade that adds
    // a new state can't silently fall through unmapped (the `when` in toTaskState() has no
    // else branch, so a missing case is a compile error, not a runtime surprise — this test
    // just pins the exact mapping so an accidental edit is caught immediately here too).

    @Test
    fun `ENQUEUED maps to Enqueued`() = with(scheduler) {
        assertEquals(TaskState.Enqueued, WorkInfo.State.ENQUEUED.toTaskState())
    }

    @Test
    fun `BLOCKED maps to Enqueued`() = with(scheduler) {
        assertEquals(TaskState.Enqueued, WorkInfo.State.BLOCKED.toTaskState())
    }

    @Test
    fun `RUNNING maps to Running`() = with(scheduler) {
        assertEquals(TaskState.Running, WorkInfo.State.RUNNING.toTaskState())
    }

    @Test
    fun `SUCCEEDED maps to Succeeded`() = with(scheduler) {
        assertEquals(TaskState.Succeeded(), WorkInfo.State.SUCCEEDED.toTaskState())
    }

    @Test
    fun `FAILED maps to Failed`() = with(scheduler) {
        assertEquals(TaskState.Failed(), WorkInfo.State.FAILED.toTaskState())
    }

    @Test
    fun `CANCELLED maps to Cancelled`() = with(scheduler) {
        assertEquals(TaskState.Cancelled, WorkInfo.State.CANCELLED.toTaskState())
    }
}
