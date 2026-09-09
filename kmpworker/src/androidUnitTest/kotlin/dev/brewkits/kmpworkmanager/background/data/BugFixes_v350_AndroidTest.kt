package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskState
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression net for the v3.5.0 Android fixes.
 *
 * **The bug.** v3.4.1 taught `OverflowFileRegistry.register` to delete the overflow file a
 * task id previously pointed at, so a re-scheduled large-input task would not leak the old
 * one. But `register` is called from `buildWorkData`, i.e. while the WorkRequest is being
 * *built* — which happens before `enqueueUniqueWork` decides whether
 * `ExistingWorkPolicy.KEEP` will discard that request. Under KEEP the stale file being
 * deleted belonged to the task WorkManager was about to keep, so the kept task's payload was
 * destroyed before it ever ran: `BaseKmpWorker.resolveInputJson` logged "Overflow input file
 * missing" and handed the worker `null`, surfacing as `Failure("Input is null")` rather than
 * as the payload loss it actually was.
 *
 * `scheduleExactAlarm` never had the bug because it short-circuits KEEP before touching the
 * registry; the fix gives the WorkManager paths the same guard.
 *
 * Naming convention: `BugFixes_v###_AndroidTest` per CLAUDE.md — one file per release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BugFixes_v350_AndroidTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    /** Comfortably over NativeTaskScheduler.OVERFLOW_THRESHOLD_BYTES (8 KB), so it spills. */
    private fun largeInput(marker: String): String =
        """{"marker":"$marker","padding":"${"x".repeat(12_000)}"}"""

    /**
     * A delay long enough that WorkManager's test executor leaves the request ENQUEUED
     * instead of running it to completion synchronously — the KEEP guard is only meaningful
     * against work that is genuinely still pending.
     */
    private val pendingTrigger get() = TaskTrigger.OneTime(initialDelayMs = 60 * 60 * 1000L)

    private suspend fun assertStillPending(id: String) {
        val state = scheduler.observeTaskState(id).first()
        assertTrue(
            state is TaskState.Enqueued || state is TaskState.Running,
            "test precondition: '$id' must still be pending for the KEEP guard to apply, was $state",
        )
    }

    private fun overflowFiles(): List<File> =
        context.cacheDir.listFiles { f: File -> f.name.startsWith("kmp_input_") }?.toList() ?: emptyList()

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
        overflowFiles().forEach { it.delete() }
    }

    @AfterTest
    fun tearDown() {
        overflowFiles().forEach { it.delete() }
    }

    @Test
    fun keepPolicy_doesNotDestroyThePendingTasksOverflowFile() = runTest {
        val id = "keep-overflow-${kotlin.random.Random.nextInt()}"
        val original = largeInput("ORIGINAL-PAYLOAD")

        assertEquals(
            ScheduleResult.ACCEPTED,
            scheduler.enqueue(
                id = id,
                trigger = pendingTrigger,
                workerClassName = "SomeWorker",
                inputJson = original,
                policy = ExistingPolicy.REPLACE,
            ),
        )

        val spilled = overflowFiles()
        assertEquals(1, spilled.size, "a >8 KB input must spill to exactly one cacheDir file")
        val originalFile = spilled.single()
        assertTrue(originalFile.readText().contains("ORIGINAL-PAYLOAD"))
        assertStillPending(id)

        // Re-enqueue the SAME id under KEEP — the canonical "make sure this is scheduled"
        // idiom apps run on every launch.
        assertEquals(
            ScheduleResult.ACCEPTED,
            scheduler.enqueue(
                id = id,
                trigger = pendingTrigger,
                workerClassName = "SomeWorker",
                inputJson = largeInput("SECOND-PAYLOAD"),
                policy = ExistingPolicy.KEEP,
            ),
        )

        assertTrue(
            originalFile.exists(),
            "KEEP kept the original task, so its overflow input file must still exist — " +
                "deleting it hands the worker a null input when it finally runs",
        )
        assertTrue(
            originalFile.readText().contains("ORIGINAL-PAYLOAD"),
            "the kept task's payload must be unchanged",
        )
    }

    @Test
    fun keepPolicy_doesNotLeakASecondOverflowFile() = runTest {
        // The discarded request must not spill a file of its own: it is never going to run,
        // and nothing would ever be able to look it up again to clean it up.
        val id = "keep-noleak-${kotlin.random.Random.nextInt()}"
        scheduler.enqueue(
            id = id,
            trigger = pendingTrigger,
            workerClassName = "SomeWorker",
            inputJson = largeInput("FIRST"),
            policy = ExistingPolicy.REPLACE,
        )
        assertEquals(1, overflowFiles().size)
        assertStillPending(id)

        repeat(3) {
            scheduler.enqueue(
                id = id,
                trigger = pendingTrigger,
                workerClassName = "SomeWorker",
                inputJson = largeInput("IGNORED-$it"),
                policy = ExistingPolicy.KEEP,
            )
        }

        assertEquals(
            1,
            overflowFiles().size,
            "three discarded KEEP re-enqueues must not accumulate orphaned overflow files",
        )
    }

    @Test
    fun replacePolicy_stillReplacesTheOverflowFile() = runTest {
        // Guard against over-correcting: REPLACE must keep its v3.4.1 behaviour of deleting
        // the superseded file rather than leaking it.
        val id = "replace-overflow-${kotlin.random.Random.nextInt()}"
        scheduler.enqueue(
            id = id,
            trigger = pendingTrigger,
            workerClassName = "SomeWorker",
            inputJson = largeInput("OLD"),
            policy = ExistingPolicy.REPLACE,
        )
        val oldFile = overflowFiles().single()

        scheduler.enqueue(
            id = id,
            trigger = pendingTrigger,
            workerClassName = "SomeWorker",
            inputJson = largeInput("NEW"),
            policy = ExistingPolicy.REPLACE,
        )

        val remaining = overflowFiles()
        assertEquals(1, remaining.size, "REPLACE must leave exactly one overflow file behind")
        assertTrue(
            remaining.single().readText().contains("NEW"),
            "REPLACE must leave the NEW payload, not the superseded one",
        )
        assertTrue(!oldFile.exists(), "REPLACE must delete the superseded overflow file")
    }

    @Test
    fun keepPolicy_onAFreshIdStillSchedulesNormally() = runTest {
        // The guard must only fire when work is actually pending — KEEP on an unknown id
        // must behave exactly as before.
        val id = "keep-fresh-${kotlin.random.Random.nextInt()}"

        val result = scheduler.enqueue(
            id = id,
            trigger = pendingTrigger,
            workerClassName = "SomeWorker",
            inputJson = largeInput("FRESH"),
            policy = ExistingPolicy.KEEP,
        )

        assertEquals(ScheduleResult.ACCEPTED, result)
        assertEquals(
            1,
            overflowFiles().size,
            "a KEEP enqueue on an id with no pending work must still spill and schedule",
        )
    }
}
