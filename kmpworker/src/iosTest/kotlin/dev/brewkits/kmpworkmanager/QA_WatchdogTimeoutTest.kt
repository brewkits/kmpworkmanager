@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * QA Test: iOS Watchdog Timeout — Worker must self-terminate within 25s
 *
 * iOS kills apps that exceed the BGTask time budget (~30s for BGAppRefreshTask).
 * The library uses `withTimeout(25_000)` inside SingleTaskExecutor as a safety margin.
 *
 * These tests verify:
 * 1. A hanging worker (infinite loop/indefinite suspend) is forcibly terminated.
 * 2. The result is Failure, NOT a hang or a Crashlytics Watchdog termination.
 * 3. The failure message identifies the timeout cause clearly.
 * 4. A normally completing worker is NOT timed out prematurely.
 * 5. A worker that finishes just under the limit completes successfully.
 *
 * Note: Tests use coroutine virtual time (runTest). The real-device watchdog
 * scenario is validated by the fact that SingleTaskExecutor's withTimeout fires
 * before iOS's 30s hard kill, which these unit tests confirm structurally.
 *
 * Run: ./gradlew :kmpworker:iosSimulatorArm64Test  (or iosX64Test on Intel)
 */
class QA_WatchdogTimeoutTest {

    // ─────────────────────────────────────────────────────────────────────────
    // TC-01: Worker that hangs indefinitely is killed by the 25s timeout
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc01_hangingWorker_isTerminatedByTimeout_notByWatchdog() = runTest {
        val hangingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        delay(Long.MAX_VALUE) // Simulates an infinite loop / deadlock
                        return WorkerResult.Success() // Unreachable
                    }
                }
        }

        val executor = SingleTaskExecutor(hangingFactory)
        // Use default 25s timeout (same as BGAppRefreshTask guard)
        val result = executor.executeTask("HangingWorker", input = null)

        // Must be Failure — the test must NOT hang indefinitely
        assertIs<WorkerResult.Failure>(result,
            "Hanging worker must be terminated and return Failure, not hang the test")
        assertTrue(
            result.message.contains("Timed out", ignoreCase = true),
            "Failure message must indicate the cause: '${result.message}'"
        )

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-02: Failure message includes the exact timeout duration
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc02_timeoutFailureMessage_includesTimeoutMs() = runTest {
        val customTimeoutMs = 5_000L
        val hangingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        delay(Long.MAX_VALUE)
                        return WorkerResult.Success()
                    }
                }
        }

        val executor = SingleTaskExecutor(hangingFactory)
        val result = executor.executeTask("HangingWorker", input = null, timeoutMs = customTimeoutMs)

        assertIs<WorkerResult.Failure>(result)
        assertTrue(
            result.message.contains("5000"),
            "Failure message must include timeout duration (5000ms): '${result.message}'"
        )

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-03: Worker that completes quickly is NOT timed out prematurely
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc03_fastWorker_completesSuccessfully_noSpuriousTimeout() = runTest {
        val fastFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        delay(100) // Very fast — well within 25s limit
                        return WorkerResult.Success(message = "Done in 100ms")
                    }
                }
        }

        val executor = SingleTaskExecutor(fastFactory)
        val result = executor.executeTask("FastWorker", input = null)

        assertIs<WorkerResult.Success>(result,
            "Fast worker must complete successfully, not be timed out prematurely")
        assertTrue(
            result.message?.contains("Done") == true,
            "Success message must be from worker: '${result.message}'"
        )

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-04: Worker that finishes just UNDER the timeout limit succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc04_workerUnderLimit_succeeds_notCancelledEarly() = runTest {
        val customTimeoutMs = 10_000L
        val nearLimitFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        delay(customTimeoutMs - 1_000) // 1s before deadline
                        return WorkerResult.Success(message = "Completed just before deadline")
                    }
                }
        }

        val executor = SingleTaskExecutor(nearLimitFactory)
        val result = executor.executeTask("NearLimitWorker", input = null, timeoutMs = customTimeoutMs)

        assertIs<WorkerResult.Success>(result,
            "Worker completing 1s before timeout must succeed, not be cancelled: $result")

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-05: Worker that finishes just OVER the timeout limit is terminated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc05_workerOverLimit_isTerminated() = runTest {
        val customTimeoutMs = 10_000L
        val overLimitFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        delay(customTimeoutMs + 1_000) // 1s over deadline
                        return WorkerResult.Success() // Must never reach here
                    }
                }
        }

        val executor = SingleTaskExecutor(overLimitFactory)
        val result = executor.executeTask("OverLimitWorker", input = null, timeoutMs = customTimeoutMs)

        assertIs<WorkerResult.Failure>(result,
            "Worker exceeding timeout must return Failure, not Success")
        assertTrue(
            result.message.contains("Timed out", ignoreCase = true),
            "Failure message must indicate timeout: '${result.message}'"
        )

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-06: Worker that throws an exception returns Failure (not a crash)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc06_workerThrows_returnFailure_noUnhandledCrash() = runTest {
        val throwingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        throw RuntimeException("Simulated worker crash")
                    }
                }
        }

        val executor = SingleTaskExecutor(throwingFactory)
        val result = executor.executeTask("ThrowingWorker", input = null)

        assertIs<WorkerResult.Failure>(result,
            "A worker exception must be caught and returned as Failure, not crash the app")
        assertTrue(
            result.message.contains("Exception", ignoreCase = true),
            "Failure message must indicate exception: '${result.message}'"
        )
        assertFalse(
            result.message.contains("Timed out", ignoreCase = true),
            "Exception failure must not be misclassified as a timeout"
        )

        executor.cleanup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-07: Worker input is passed through correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc07_inputJson_passedToWorker_unmodified() = runTest {
        val testInput = """{"userId":42,"action":"sync"}"""
        var receivedInput: String? = null

        val echoFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        receivedInput = input
                        return WorkerResult.Success(message = "Echo: $input")
                    }
                }
        }

        val executor = SingleTaskExecutor(echoFactory)
        executor.executeTask("EchoWorker", input = testInput)

        assertTrue(
            receivedInput == testInput,
            "Worker must receive exactly the original inputJson: got '$receivedInput'"
        )

        executor.cleanup()
    }
}
