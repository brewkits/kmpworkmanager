package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Regression net for the v2.5.0 exception-classification policy in [BaseKmpWorker].
 *
 * **Background**: when a worker throws, [BaseKmpWorker.doWorkInternal] must decide
 * between `Result.failure()` (permanent, no more retries) and `Result.retry()`
 * (transient, WorkManager backoff applies). The cost asymmetry is severe:
 *
 *  - **False positive (transient → permanent)**: user payload silently lost. No more
 *    runs scheduled.
 *  - **False negative (permanent → transient)**: bounded waste — WorkManager backoff +
 *    optional `attemptCap` cap the retry count.
 *
 * v2.4.x previously classified `NullPointerException`, `IllegalArgumentException`, and
 * `NumberFormatException` as permanent. In practice these are thrown frequently by
 * third-party SDKs and JSON parsers when a transient server response is null/empty
 * — leading to data loss on flaky networks. The fix narrows the permanent list to
 * exception types that are *always* programming/config errors at the worker boundary.
 *
 * This test pins the new policy. If someone re-adds NPE/IAE/NFE to the permanent
 * list, both halves of the policy break:
 *  1. Transient-style exceptions still produce `Result.retry()` (data-preservation).
 *  2. Genuine permanent types still produce `Result.failure()` (no quota waste).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V250ExceptionClassificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun nullPointerException_isTransient_returnsRetry() {
        val result = runWorkerThrowing(NullPointerException("server returned null field"))
        assertTrue(
            result is Result.Retry,
            "NPE must be retried — it's thrown by SDKs/parsers on transient null server " +
                "responses. If permanent, user data on flaky networks is lost. Got: $result"
        )
    }

    @Test
    fun illegalArgumentException_isTransient_returnsRetry() {
        val result = runWorkerThrowing(IllegalArgumentException("invalid arg from upstream"))
        assertTrue(
            result is Result.Retry,
            "IllegalArgumentException must be retried — third-party SDKs commonly throw " +
                "IAE on transient state. Got: $result"
        )
    }

    @Test
    fun numberFormatException_isTransient_returnsRetry() {
        val result = runWorkerThrowing(NumberFormatException("can't parse \"\" as Int"))
        assertTrue(
            result is Result.Retry,
            "NumberFormatException must be retried — empty/malformed numeric fields from " +
                "servers are recoverable on the next call. Got: $result"
        )
    }

    @Test
    fun serializationException_isPermanent_returnsFailure() {
        val result = runWorkerThrowing(SerializationException("schema mismatch"))
        assertTrue(
            result is Result.Failure,
            "SerializationException is a permanent schema/encoder mismatch — retry would " +
                "waste quota. Got: $result"
        )
    }

    @Test
    fun classNotFoundException_isPermanent_returnsFailure() {
        val result = runWorkerThrowing(ClassNotFoundException("worker class removed"))
        assertTrue(
            result is Result.Failure,
            "ClassNotFoundException: worker class is gone from the binary. Retry can " +
                "never succeed. Got: $result"
        )
    }

    private fun runWorkerThrowing(exception: Throwable): Result {
        // PolicyTestWorker reads the factory from a static field during construction,
        // so we MUST set it before TestListenableWorkerBuilder calls the constructor
        // via reflection.
        PolicyTestWorker.factoryHolder = AndroidWorkerFactory_PolicyTest(exception)

        val inputData = Data.Builder()
            .putString("workerClassName", "ThrowingWorker")
            .build()

        val worker = TestListenableWorkerBuilder<PolicyTestWorker>(context)
            .setInputData(inputData)
            .build()
        return runBlocking { worker.doWork() }
    }
}

private class AndroidWorkerFactory_PolicyTest(private val toThrow: Throwable) : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker = object : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
            throw toThrow
        }
    }
}

/**
 * Public top-level worker required for [TestListenableWorkerBuilder] reflection.
 * Reads the factory from a thread-local-style companion field so each test can
 * inject its own throwing behaviour without touching Koin.
 */
class PolicyTestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : BaseKmpWorker(appContext, workerParams, factoryHolder!!) {

    override val workerLogTag: String get() = "PolicyTestWorker"
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
