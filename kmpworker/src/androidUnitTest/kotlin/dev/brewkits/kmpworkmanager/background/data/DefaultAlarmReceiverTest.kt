package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression net for the fixed bug documented in [DefaultAlarmReceiver]'s own KDoc: this
 * class used to only log the alarm and finish the `PendingResult` without ever invoking
 * the scheduled worker, so every `TaskTrigger.Exact` task fired its `AlarmManager` alarm
 * on time but the work itself silently never ran. This test asserts the worker is
 * actually resolved via [KmpWorkManagerAndroid]'s registry and its `doWork` is invoked —
 * not just that no exception was thrown.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultAlarmReceiverTest {

    private class RecordingWorker(private val result: WorkerResult) : AndroidWorker {
        val invocations = AtomicInteger(0)
        var lastInput: String? = null

        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            invocations.incrementAndGet()
            lastInput = input
            return result
        }
    }

    private class TestFactory(private val workers: Map<String, RecordingWorker>) : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = workers[workerClassName]
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @After
    fun tearDown() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @Test
    fun doAlarmWork_resolvesAndInvokesTheScheduledWorker() = runBlocking {
        val worker = RecordingWorker(WorkerResult.Success())
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(mapOf("RecordingWorker" to worker)))

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-1",
            workerClassName = "RecordingWorker",
            inputJson = """{"foo":"bar"}"""
        )

        assertEquals(1, worker.invocations.get(), "worker.doWork must actually run — this is the exact bug this receiver used to have")
        assertEquals("""{"foo":"bar"}""", worker.lastInput)
    }

    @Test
    fun doAlarmWork_whenWorkerNotFound_doesNotThrow() = runBlocking {
        KmpWorkManager.initialize(context = context, workerFactory = TestFactory(emptyMap()))

        // Must complete without throwing — DefaultAlarmReceiver runs inside a
        // BroadcastReceiver's coroutine scope where an uncaught exception would crash
        // the host process.
        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-missing",
            workerClassName = "NoSuchWorker",
            inputJson = null
        )
    }

    @Test
    fun doAlarmWork_whenNotInitialized_doesNotThrow() = runBlocking {
        // KmpWorkManager.shutdown() already ran in setUp() — registry is absent.
        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-uninitialized",
            workerClassName = "AnyWorker",
            inputJson = null
        )
    }

    @Test
    fun doAlarmWork_whenWorkerThrows_doesNotPropagate() = runBlocking {
        KmpWorkManager.initialize(
            context = context,
            workerFactory = object : AndroidWorkerFactory {
                override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        throw RuntimeException("boom")
                    }
                }
            }
        )

        DefaultAlarmReceiver().doAlarmWork(
            context = context,
            taskId = "exact-task-throws",
            workerClassName = "ThrowingWorker",
            inputJson = null
        )
        // Reaching here means the exception was caught internally, as intended.
        assertTrue(true)
    }
}
