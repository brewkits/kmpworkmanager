package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression net for the v3.0.2 `maxRetries` ceiling on the [WorkerResult.Failure]
 * legacy-retry path in [BaseKmpWorker].
 *
 * **Background**: `Failure(shouldRetry = true)` previously retried forever — WorkManager
 * kept rescheduling with no worker-visible ceiling. The [WorkerResult.Retry] variant has
 * always honored an `attemptCap`, but the legacy boolean path had none, so a plugin that
 * only exposes `Failure(shouldRetry = true)` could never bound its retries.
 *
 * The fix reads an explicit `maxRetries` from `inputData` (stamped by the caller/plugin):
 *  - `maxRetries = N` → at most `N + 1` total runs (1 initial + N retries). Exhausted once
 *    `runAttemptCount >= N` (runAttemptCount is 0-based).
 *  - key absent → `-1` → uncapped, preserving behaviour for existing WorkRequests.
 *
 * This mirrors iOS `DynamicTaskDispatcher.handleOneTimeResult` (`effectiveCap = 1 + maxRetries`)
 * so the two platforms agree on what `maxRetries` means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V302RetryCapTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- Stamp side: NativeTaskScheduler writes Constraints.maxRetries into the WorkRequest ---
    //
    // WorkManager's public WorkInfo API does not expose a queued request's input Data, so an
    // end-to-end "scheduler stamped the key" assertion is only reachable by running the worker
    // (covered by the read-side tests above) plus a device integration test. Here we pin the
    // contract seam both sides depend on: the default is uncapped and a missing key reads back
    // as -1, so BaseKmpWorker never treats an unset ceiling as exhausted.

    @Test
    fun defaultConstraints_isUncapped() {
        assertEquals(-1, Constraints().maxRetries, "Default Constraints.maxRetries must be -1 (uncapped).")
    }

    @Test
    fun absentKey_readsBackAsUncapped_neverExhausted() {
        val absentReads = Data.Builder().build().getInt(NativeTaskScheduler.KEY_MAX_RETRIES, -1)
        assertEquals(-1, absentReads, "Missing key must read as the -1 uncapped default.")
        assertFalse(absentReads in 0..0, "-1 must never register as exhausted at attempt 0.")
    }

    @Test
    fun absentMaxRetries_retriesUnbounded_backCompat() {
        // No "maxRetries" key → -1 → uncapped. Even at a high run attempt count the
        // shouldRetry hint must still schedule a retry, matching pre-3.0.2 behaviour.
        val result = runRetryableFailure(maxRetries = null, runAttemptCount = 99)
        assertTrue(
            result is Result.Retry,
            "Absent maxRetries must stay uncapped so existing WorkRequests keep retrying. Got: $result"
        )
    }

    @Test
    fun belowCap_retries() {
        // maxRetries=2 → runs allowed at attempt 0 and 1 (retries remaining). attempt 1 < 2.
        val result = runRetryableFailure(maxRetries = 2, runAttemptCount = 1)
        assertTrue(
            result is Result.Retry,
            "runAttemptCount(1) < maxRetries(2): a retry is still owed. Got: $result"
        )
    }

    @Test
    fun atCap_failsPermanently() {
        // maxRetries=2 → exhausted once runAttemptCount >= 2. This is the 3rd run (1 initial
        // + 2 retries) and must be the last.
        val result = runRetryableFailure(maxRetries = 2, runAttemptCount = 2)
        assertTrue(
            result is Result.Failure,
            "runAttemptCount(2) >= maxRetries(2): retries exhausted, must fail permanently. Got: $result"
        )
    }

    @Test
    fun beyondCap_failsPermanently() {
        val result = runRetryableFailure(maxRetries = 2, runAttemptCount = 5)
        assertTrue(
            result is Result.Failure,
            "runAttemptCount(5) beyond maxRetries(2) must fail permanently, never retry. Got: $result"
        )
    }

    @Test
    fun zeroMaxRetries_neverRetries() {
        // maxRetries=0 → exhausted at runAttemptCount 0: the initial run is the only run.
        val result = runRetryableFailure(maxRetries = 0, runAttemptCount = 0)
        assertTrue(
            result is Result.Failure,
            "maxRetries=0 means the initial run is the only attempt — no retry. Got: $result"
        )
    }

    @Test
    fun nonRetryableFailure_ignoresMaxRetries() {
        // shouldRetry=false must fail regardless of a generous maxRetries.
        val result = runRetryableFailure(maxRetries = 10, runAttemptCount = 0, shouldRetry = false)
        assertTrue(
            result is Result.Failure,
            "Failure(shouldRetry=false) must not retry even when maxRetries is high. Got: $result"
        )
    }

    // --- WorkerResult.Retry branch: honors Constraints.maxRetries when no per-result attemptCap ---

    @Test
    fun retryResult_belowMaxRetries_retries() {
        // maxRetries=2 → cap 3 total runs. runAttemptCount 1 → 1+1=2 < 3 → still retry.
        val result = runRetryResult(maxRetries = 2, runAttemptCount = 1)
        assertTrue(
            result is Result.Retry,
            "WorkerResult.Retry below the maxRetries-derived cap must retry. Got: $result"
        )
    }

    @Test
    fun retryResult_atMaxRetries_failsPermanently() {
        // maxRetries=2 → cap 3. runAttemptCount 2 → 2+1=3 >= 3 → cap reached.
        val result = runRetryResult(maxRetries = 2, runAttemptCount = 2)
        assertTrue(
            result is Result.Failure,
            "WorkerResult.Retry at the maxRetries-derived cap must fail, not retry forever. Got: $result"
        )
    }

    @Test
    fun retryResult_absentMaxRetries_retriesUnbounded() {
        val result = runRetryResult(maxRetries = null, runAttemptCount = 99)
        assertTrue(
            result is Result.Retry,
            "WorkerResult.Retry with no attemptCap and no maxRetries stays uncapped (WorkManager quota governs). Got: $result"
        )
    }

    @Test
    fun retryResult_attemptCap_winsOverMaxRetries() {
        // attemptCap=2 (per-result) must win over the looser maxRetries=10.
        val result = runRetryResult(maxRetries = 10, runAttemptCount = 1, attemptCap = 2)
        assertTrue(
            result is Result.Failure,
            "Per-result attemptCap=2 must cap at 2 total runs, overriding maxRetries=10. Got: $result"
        )
    }

    private fun runRetryableFailure(
        maxRetries: Int?,
        runAttemptCount: Int,
        shouldRetry: Boolean = true
    ): Result = runWorker(
        factory = AndroidWorkerFactory_RetryCap(WorkerResult.Failure("boom", shouldRetry = shouldRetry)),
        maxRetries = maxRetries,
        runAttemptCount = runAttemptCount
    )

    private fun runRetryResult(
        maxRetries: Int?,
        runAttemptCount: Int,
        attemptCap: Int? = null
    ): Result = runWorker(
        factory = AndroidWorkerFactory_RetryCap(WorkerResult.Retry("flaky", attemptCap = attemptCap)),
        maxRetries = maxRetries,
        runAttemptCount = runAttemptCount
    )

    private fun runWorker(
        factory: AndroidWorkerFactory,
        maxRetries: Int?,
        runAttemptCount: Int
    ): Result {
        RetryCapTestWorker.factoryHolder = factory

        val inputBuilder = Data.Builder()
            .putString("workerClassName", "FailingWorker")
        if (maxRetries != null) inputBuilder.putInt("maxRetries", maxRetries)

        val worker = TestListenableWorkerBuilder<RetryCapTestWorker>(context)
            .setInputData(inputBuilder.build())
            .setRunAttemptCount(runAttemptCount)
            .build()
        return runBlocking { worker.doWork() }
    }
}

private class AndroidWorkerFactory_RetryCap(private val result: WorkerResult) : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = result
    }
}

class RetryCapTestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : BaseKmpWorker(appContext, workerParams, factoryHolder!!) {

    override val workerLogTag: String get() = "RetryCapTestWorker"
    override suspend fun doWork(): Result = doWorkInternal()
    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)
            ?: return WorkerResult.Failure("not found")
        return worker.doWork(inputJson, WorkerEnvironment(
            progressListener = object : dev.brewkits.kmpworkmanager.background.domain.ProgressListener {
                override fun onProgressUpdate(progress: dev.brewkits.kmpworkmanager.background.domain.WorkerProgress) {}
            },
            isCancelled = { false }
        ))
    }

    companion object {
        @Volatile
        var factoryHolder: AndroidWorkerFactory? = null
    }
}
