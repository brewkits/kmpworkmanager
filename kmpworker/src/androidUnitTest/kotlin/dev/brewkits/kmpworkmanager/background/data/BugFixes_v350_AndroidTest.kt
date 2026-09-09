package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.OverwritingInputMerger
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskState
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
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
import kotlin.test.assertFailsWith
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

    // ── Shared Data budget across a chain hop ─────────────────────────────────────────
    //
    // A chain step's input and its predecessor's output never travel separately:
    // WorkManager's WorkerWrapper merges every prerequisite's outputData into the
    // successor's inputData through an InputMerger before doWork() is called, and
    // InputMerger.merge ends in Data.Builder.build(), which enforces the 10 240-byte cap.
    // Both sides used to be checked independently against OVERFLOW_THRESHOLD_BYTES (8 KB),
    // so a legal pair met at ~16 KB and killed the chain inside WorkerWrapper — before any
    // worker code could observe or report it.

    /** The chain stamps every step's Data carries, sized as production writes them. */
    private fun stampedInput(payloadBytes: Int): Data = Data.Builder()
        .putString("workerClassName", "dev.brewkits.example.SomeReasonablyLongWorkerName")
        .putInt(NativeTaskScheduler.KEY_MAX_RETRIES, 3)
        .putString(NativeTaskScheduler.KEY_CHAIN_ID, "chain-${"c".repeat(36)}")
        .putInt(NativeTaskScheduler.KEY_STEP_INDEX, 1)
        .putInt(NativeTaskScheduler.KEY_TOTAL_STEPS, 5)
        .putLong(NativeTaskScheduler.KEY_DEADLINE_MS, 1_700_000_000_000L)
        .putBoolean(NativeTaskScheduler.KEY_MERGE_PREVIOUS_OUTPUT, true)
        .putString("inputJson", "x".repeat(payloadBytes))
        .build()

    private fun stepOutput(payloadBytes: Int): Data = Data.Builder()
        .putString(NativeTaskScheduler.KEY_STEP_OUTPUT, "y".repeat(payloadBytes))
        .build()

    @Test
    fun chainStepBudgets_surviveTheInputMergerThatJoinsThem() {
        val merged = OverwritingInputMerger().merge(
            listOf(
                stampedInput(NativeTaskScheduler.CHAIN_STEP_INPUT_BUDGET_BYTES),
                stepOutput(NativeTaskScheduler.CHAIN_STEP_OUTPUT_BUDGET_BYTES),
            ),
        )
        // Merging is the assertion — build() throws when the result exceeds the cap. The size
        // check pins the remaining headroom so a later budget bump cannot quietly eat it.
        val size = Data.toByteArrayInternal(merged).size
        assertTrue(
            size <= NativeTaskScheduler.MAX_WORK_DATA_BYTES,
            "a worst-case chain hop serialized to $size bytes, over WorkManager's " +
                "${NativeTaskScheduler.MAX_WORK_DATA_BYTES}-byte cap",
        )
    }

    @Test
    fun theOldIndependent8KbCaps_wouldHaveExceededTheDataCap() {
        // The bug, pinned. Both halves are individually legal under the old threshold and
        // both build() fine on their own; only the merge fails. Kept as a live control so a
        // future change that widens the budgets back toward 8 KB fails here first.
        val input = stampedInput(NativeTaskScheduler.OVERFLOW_THRESHOLD_BYTES)
        val output = stepOutput(NativeTaskScheduler.OVERFLOW_THRESHOLD_BYTES)

        val error = assertFailsWith<IllegalStateException> {
            OverwritingInputMerger().merge(listOf(input, output))
        }
        assertTrue(
            error.message.orEmpty().contains("10240"),
            "expected WorkManager's Data size guard, got: ${error.message}",
        )
    }

    @Test
    fun chainStepInputOverBudget_spillsToAFileInsteadOfRidingInTheData() = runTest {
        // The input side of the budget, through the real scheduler. 3 KB is under the
        // standalone 8 KB threshold but over the chain step's, so it must spill — losslessly,
        // which is the whole reason input got the smaller share of the budget.
        val payload = """{"padding":"${"x".repeat(3_000)}"}"""
        val chainId = "budget-chain-${kotlin.random.Random.nextInt()}"

        scheduler.enqueueChain(
            scheduler.beginWith(
                dev.brewkits.kmpworkmanager.background.domain.TaskRequest(
                    workerClassName = "SomeWorker",
                    inputJson = payload,
                ),
            ),
            id = chainId,
            policy = ExistingPolicy.REPLACE,
        )

        assertEquals(
            1,
            overflowFiles().size,
            "a chain step over the input budget must spill to cacheDir rather than ride in the Data",
        )
    }

    // ── I-23: WorkManager's flex clamp happened in silence ────────────────────────────
    //
    // `flexMs` is coerced up to WorkManager's 5-minute floor and down to the interval. Both
    // are required, but neither was reported: a caller who asked for a 60s window got 300s
    // and could only discover it by watching when the task actually ran. Open as I-23 since
    // v2.4.2.

    private class CapturingLogger : CustomLogger {
        val warnings = mutableListOf<String>()
        override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
            if (level == Logger.Level.WARN) warnings += message
        }
    }

    private fun withCapturedLogs(block: () -> Unit): List<String> {
        val capturing = CapturingLogger()
        Logger.setCustomLogger(capturing)
        try {
            block()
        } finally {
            Logger.setCustomLogger(null)
        }
        return capturing.warnings
    }

    @Test
    fun explicitFlexBelowTheWorkManagerMinimum_warnsThatItWasClamped() = runTest {
        val warnings = withCapturedLogs {
            kotlinx.coroutines.runBlocking {
                scheduler.enqueue(
                    id = "flex-clamped-${kotlin.random.Random.nextInt()}",
                    trigger = TaskTrigger.Periodic(intervalMs = 20 * 60 * 1000L, flexMs = 60_000L),
                    workerClassName = "SomeWorker",
                )
            }
        }

        val clampWarning = warnings.singleOrNull { it.contains("clamped") }
        assertTrue(
            clampWarning != null && clampWarning.contains("60000") && clampWarning.contains("300000"),
            "expected a warning naming the requested and effective flex windows, got: $warnings",
        )
    }

    @Test
    fun noExplicitFlex_doesNotWarn_evenThoughTheDerivedDefaultIsClampedToo() = runTest {
        // The library derives flexMs = interval/2 when the caller does not set it, and that
        // derived value is clamped by the same code. Warning about it would fire on every
        // periodic schedule and train people to ignore the message — the warning is for a
        // request the caller actually made.
        val warnings = withCapturedLogs {
            kotlinx.coroutines.runBlocking {
                scheduler.enqueue(
                    id = "flex-default-${kotlin.random.Random.nextInt()}",
                    trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000L),
                    workerClassName = "SomeWorker",
                )
            }
        }

        assertTrue(
            warnings.none { it.contains("clamped") },
            "the library's own derived default must not produce a clamp warning, got: $warnings",
        )
    }
}
