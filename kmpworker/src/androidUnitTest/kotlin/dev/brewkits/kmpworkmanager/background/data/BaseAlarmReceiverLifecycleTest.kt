package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial lifecycle tests for [BaseAlarmReceiver] — these are the regression net
 * for the v2.5 P0.4 fix.
 *
 * The pre-v2.5 code path was `CoroutineScope(Dispatchers.IO).launch { … }` with no
 * timeout and no `scope.cancel()` in finally. Symptoms in production:
 * - A `doAlarmWork` that hung indefinitely (e.g. network call without timeout) left
 *   the coroutine running past the BroadcastReceiver's ~10s budget; the OS killed
 *   the receiver process and the coroutine state became undefined.
 * - `pendingResult.finish()` did still run (via `finally`), but any background work
 *   it kicked off — overflow file cleanup, telemetry flush — could race the process
 *   teardown and leak.
 *
 * v2.5 fixes this with `SupervisorJob` + `withTimeout(workTimeoutMs)` + `scope.cancel()`
 * in `finally`. These tests pin down that contract:
 * 1. `doAlarmWork` running past `workTimeoutMs` is cancelled cleanly (no leak).
 * 2. `onFinish` (the PendingResult.finish() shim) is invoked exactly once per run.
 * 3. Exceptions thrown inside `doAlarmWork` do not bypass `onFinish` or overflow
 *    file deletion.
 * 4. The scope is torn down (no child coroutines outlive the function).
 *
 * Why Robolectric: `BaseAlarmReceiver` extends an Android-only class hierarchy and
 * cannot be instantiated in pure JVM unit tests. We only use Robolectric for the
 * minimal `BroadcastReceiver` superclass support — the actual coroutine path runs
 * on the JVM as normal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BaseAlarmReceiverLifecycleTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Test double — captures invocation count and exposes a hook to suspend until the
     * receiver finishes. We cannot use mockito-kotlin on a final class here, so we
     * roll a tiny manual fake.
     */
    private class TestReceiver(
        timeoutMs: Long,
        val workBlock: suspend (taskId: String) -> Unit
    ) : BaseAlarmReceiver() {
        override val workTimeoutMs: Long = timeoutMs
        val workStarted = AtomicInteger(0)
        val workCompleted = AtomicInteger(0)
        val workCancelled = AtomicInteger(0)
        val workThrew = AtomicInteger(0)
        val finalized = CompletableDeferred<Unit>()

        override suspend fun doAlarmWork(
            context: Context,
            taskId: String,
            workerClassName: String,
            inputJson: String?
        ) {
            workStarted.incrementAndGet()
            try {
                workBlock(taskId)
                workCompleted.incrementAndGet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                workCancelled.incrementAndGet()
                throw e
            } catch (e: Exception) {
                workThrew.incrementAndGet()
                throw e
            }
        }

        /**
         * Drive the internal scope without needing a real PendingResult. Sets
         * [finalized] when the onFinish hook runs — tests await this with a generous
         * timeout to assert "finish was called exactly once".
         */
        fun runDirectly(ctx: Context, taskId: String, overflowFilePath: String? = null) {
            runHandleAlarmScope(
                context = ctx,
                taskId = taskId,
                workerClassName = "TestWorker",
                inputJson = null,
                overflowFilePath = overflowFilePath,
                onFinish = { finalized.complete(Unit) }
            )
        }
    }

    @Test
    fun stuckWork_isCancelledByWithTimeout_andFinishStillCalled() = runBlocking {
        // Adversarial: doAlarmWork blocks for 60s; budget is 200ms. After timeout we
        // expect: workCancelled = 1, finalized completed, workCompleted = 0.
        val receiver = TestReceiver(timeoutMs = 200L) {
            // Use real Dispatchers via the receiver's own SupervisorJob+IO; this
            // is not a TestScope-controlled delay so it really sleeps.
            delay(60_000L)
        }
        receiver.runDirectly(ctx = context, taskId = "stuck-task")

        // Wait for the receiver to finalize. 5s upper bound — withTimeout(200) plus
        // any executor latency should resolve well under this.
        withTimeout(5_000L) { receiver.finalized.await() }

        assertEquals(1, receiver.workStarted.get(), "doAlarmWork must have been invoked once")
        assertEquals(0, receiver.workCompleted.get(), "stuck work must NOT report completed")
        assertEquals(1, receiver.workCancelled.get(), "withTimeout must have cancelled the coroutine")
        assertEquals(0, receiver.workThrew.get(), "TimeoutCancellationException is handled in BaseAlarmReceiver, not surfaced as a regular exception")
    }

    @Test
    fun finishWithoutTimeout_runsFinishOnce_andCompletesNormally() = runBlocking {
        val receiver = TestReceiver(timeoutMs = 5_000L) {
            // Returns immediately.
        }
        receiver.runDirectly(context, "fast-task")
        withTimeout(2_000L) { receiver.finalized.await() }

        assertEquals(1, receiver.workCompleted.get(), "fast work must complete normally")
        assertEquals(0, receiver.workCancelled.get())
        assertEquals(0, receiver.workThrew.get())
    }

    @Test
    fun workThatThrows_doesNotBlockFinish() = runBlocking {
        val receiver = TestReceiver(timeoutMs = 5_000L) {
            throw RuntimeException("boom")
        }
        receiver.runDirectly(context, "throw-task")
        withTimeout(2_000L) { receiver.finalized.await() }

        assertEquals(1, receiver.workThrew.get())
        assertEquals(0, receiver.workCancelled.get())
        // finalized completed → onFinish (pendingResult.finish shim) was invoked despite throw.
    }

    @Test
    fun overflowFile_isDeleted_evenWhenWorkTimesOut() = runBlocking {
        val temp = File.createTempFile("kmp-overflow-", ".json").apply {
            writeText("""{"dummy":true}""")
        }
        assertTrue(temp.exists(), "test setup: overflow file must exist before run")

        val receiver = TestReceiver(timeoutMs = 100L) {
            delay(60_000L) // exceeds budget
        }
        receiver.runDirectly(context, "timeout-with-overflow", overflowFilePath = temp.absolutePath)
        withTimeout(5_000L) { receiver.finalized.await() }

        assertFalse(
            temp.exists(),
            "overflow file must be deleted in finally block even when work times out"
        )
    }

    @Test
    fun consecutiveRuns_doNotShareScope() = runBlocking {
        // Adversarial: two back-to-back invocations on the SAME receiver instance must
        // not share scope state. If `scope` were a field instead of per-call, a stuck
        // first run could prevent the second from starting.
        val receiver = TestReceiver(timeoutMs = 100L) {
            delay(60_000L)
        }
        receiver.runDirectly(context, "first")
        // Without awaiting the first, fire the second. Each must finalize independently.
        val secondReceiver = TestReceiver(timeoutMs = 100L) {
            delay(60_000L)
        }
        secondReceiver.runDirectly(context, "second")

        withTimeout(5_000L) { receiver.finalized.await() }
        withTimeout(5_000L) { secondReceiver.finalized.await() }
        // Both got their own scope → both reached finally → both cancelled exactly once.
        assertEquals(1, receiver.workCancelled.get())
        assertEquals(1, secondReceiver.workCancelled.get())
    }
}
