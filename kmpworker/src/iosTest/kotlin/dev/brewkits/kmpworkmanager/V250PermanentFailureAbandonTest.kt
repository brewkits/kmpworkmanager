@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P2 bug surfaced by the proactive audit:
 * [ChainExecutor.executeStep] mapped `WorkerResult.Failure(shouldRetry = false)`
 * to `TaskOutcome(success = false, errorMessage = result.message)` without
 * forwarding the `shouldRetry` flag. As a consequence, a worker explicitly
 * signalling "this failure is permanent, do not retry" would still trigger:
 *
 *  - chain-level retries up to `MAX_RETRIES` (default 3)
 *  - per-step retries up to whatever `attemptCap` is in scope
 *  - re-enqueue of the chain for each retry, burning BGTask quota and
 *    WorkManager backoff slots on guaranteed re-failures
 *
 * **Fix**: [TaskOutcome] and [StepOutcome] gain an `isPermanentFailure: Boolean`
 * field. `Failure(shouldRetry = false)` sets it to `true`. executeChain's
 * failure-handling block adds `permanentFailure` to the `shouldAbandon`
 * condition alongside the existing `hasExceededRetries` and `capReached`
 * checks, immediately deleting chain definition + progress files.
 *
 * **Test strategy**: run a chain whose worker returns
 * `Failure(shouldRetry = false)` on the first attempt. Verify the chain is
 * abandoned on that single attempt — definition + progress files are deleted,
 * and `retryCount` never increments past 0.
 */
class V250PermanentFailureAbandonTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_permfail_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir("storage")
    )

    @Test
    fun permanentFailure_abandonsChainOnFirstAttempt() = runTest {
        val storage = makeStorage()
        val executor = ChainExecutor(
            workerFactory = object : IosWorkerFactory {
                override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                        WorkerResult.Failure("bad input — never retry", shouldRetry = false)
                }
            },
            taskType = BGTaskType.APP_REFRESH,
            fileStorage = storage
        )
        try {
            executor.resetShutdownState()

            val chainId = "perm-fail-chain"
            storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest("BadInputWorker"))))
            storage.enqueueChain(chainId)

            val executed = withContext(Dispatchers.Default) {
                executor.executeChainsInBatch(maxChains = 1)
            }
            assertEquals(0, executed, "Permanent failure → chain doesn't count as succeeded")

            // Flush so loadChainProgress sees the latest state from disk.
            storage.flushNow()

            // The contract: chain abandoned → both definition and progress deleted.
            // Pre-fix: chain would stay enqueued (or have progress with retryCount=1 ready
            // for next BGTask invocation), wasting quota on a guaranteed failure.
            assertNull(
                storage.loadChainDefinition(chainId),
                "REGRESSION: chain definition still present after Failure(shouldRetry=false). " +
                    "executeChain must honor shouldRetry=false as a permanent-failure signal " +
                    "and delete the definition immediately, not consume the chain-level retry " +
                    "budget on a contract-permanent failure."
            )
            assertNull(
                storage.loadChainProgress(chainId),
                "REGRESSION: chain progress still present after Failure(shouldRetry=false). " +
                    "Abandonment must delete progress alongside definition."
            )
        } finally {
            executor.close()
            storage.close()
        }
    }

    @Test
    fun transientFailure_doesNotAbandon_keepsRetryBudget() = runTest {
        // Sanity check: the new abandonment path must NOT fire for
        // Failure(shouldRetry = true). The chain should stay alive with progress
        // showing retry intent so the next BGTask invocation can resume it.
        val storage = makeStorage()
        val executor = ChainExecutor(
            workerFactory = object : IosWorkerFactory {
                override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
                        WorkerResult.Failure("transient network blip", shouldRetry = true)
                }
            },
            taskType = BGTaskType.APP_REFRESH,
            fileStorage = storage
        )
        try {
            executor.resetShutdownState()

            val chainId = "transient-chain"
            storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest("FlakyWorker"))))
            storage.enqueueChain(chainId)

            withContext(Dispatchers.Default) {
                executor.executeChainsInBatch(maxChains = 1)
            }
            storage.flushNow()

            // Transient failure → chain definition preserved for next attempt.
            // We don't assert exact progress fields (those are tested elsewhere);
            // just confirm definition lives. The negation of the permanent test
            // proves the abandonment path is gated correctly.
            assertNotNull(
                storage.loadChainDefinition(chainId),
                "Transient failure (shouldRetry=true) must NOT trigger the permanent-abandon " +
                    "path — the chain definition must remain on disk for the next attempt."
            )
        } finally {
            executor.close()
            storage.close()
        }
    }
}
