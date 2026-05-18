package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression net for the v2.5.0 P0 bug: when WorkManager preempted a [BaseKmpWorker]
 * (Doze, OS reclaim, constraints lost) the coroutine was cancelled — but the `finally`
 * block in `doWorkInternal()` still deleted the overflow input file. WorkManager then
 * rescheduled the worker per the WorkRequest's backoff policy, but on the rerun
 * `resolveInputJson()` returned `null` because the file was gone → user payload lost.
 *
 * The bug was a dead flag: `wasCancelled` was set in the `catch (CancellationException)`
 * branch but never read by the delete guard. The fix adds `&& !wasCancelled` to the
 * guard so cancellation preserves the file (the 24h zombie sweep is the safety net).
 *
 * This test pins:
 *  - `CancellationException` from `performWork` propagates out (cooperative cancellation)
 *  - the overflow file still exists after the worker returns
 *
 * If `wasCancelled` regresses (removed from the guard, or `wasCancelled = true` removed
 * from the catch), this test fails with "overflow file was deleted".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V250CancellationPreservesOverflowFileTest {

    private lateinit var context: Context
    private lateinit var overflowFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        overflowFile = File(
            context.cacheDir,
            "kmp_input_cancel_${System.nanoTime()}.json"
        ).apply {
            writeText("""{"payload":"user data that must survive WorkManager preempt"}""")
        }
        assertTrue(overflowFile.exists(), "test setup: overflow file must exist")
    }

    @After
    fun tearDown() {
        overflowFile.delete()
    }

    @Test
    fun cancellationException_preservesOverflowFile_forReschedule() {
        val inputData = Data.Builder()
            .putString("workerClassName", "CancellingTestWorker")
            .putString(NativeTaskScheduler.KEY_INPUT_JSON_FILE, overflowFile.absolutePath)
            .build()

        val worker = TestListenableWorkerBuilder<CancellingTestWorker>(context)
            .setInputData(inputData)
            .build()

        try {
            runBlocking { worker.doWork() }
            fail("expected CancellationException to propagate out of doWork()")
        } catch (e: CancellationException) {
            // Expected — this is the OS-preempt path.
        }

        assertTrue(
            overflowFile.exists(),
            "REGRESSION: overflow file was deleted on CancellationException. " +
                "WorkManager will reschedule this worker per its backoff policy, but " +
                "resolveInputJson() will now return null → user payload lost. " +
                "Check that BaseKmpWorker.doWorkInternal()'s finally guard still has " +
                "`&& !wasCancelled`."
        )
    }
}

/**
 * Public top-level worker so AndroidX [TestListenableWorkerBuilder] can find its
 * (Context, WorkerParameters) constructor via reflection. Overrides `performWork`
 * to simulate the OS-preempt path without needing a real worker factory.
 */
class CancellingTestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : BaseKmpWorker(appContext, workerParams, NoopFactory) {

    override val workerLogTag: String get() = "CancellingTestWorker"

    override suspend fun doWork(): androidx.work.ListenableWorker.Result = doWorkInternal()

    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        throw CancellationException("Simulated WorkManager preempt (Doze / stop / constraint)")
    }

    private object NoopFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = null
    }
}
