@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P1 bug: nested `withTimeout` blocks in
 * [ChainExecutor.executeChain] (inner: `chainTimeout`) and
 * [ChainExecutor.executeChainsInBatch] (outer: `totalTimeoutMs`).
 *
 * **Bug**: when the OUTER timeout fires, the resulting
 * `TimeoutCancellationException` propagates down through the inner
 * `withTimeout(chainTimeout)` block. The inner `catch (TimeoutCancellationException)`
 * catches it indiscriminately — it cannot tell from the exception alone whether the
 * inner or outer timer fired. Pre-fix, the catch:
 *
 *   1. Logged `"Chain timed out (chainTimeout ms)"` — wrong, the chain ran for only
 *      a fraction of chainTimeout
 *   2. Set [ExecutionStatus.TIMEOUT] in the history record — wrong, the chain didn't
 *      hit its own deadline
 *   3. Returned `false` instead of rethrowing — defeating the outer's cancellation
 *
 * **Fix**: compare elapsed wall-clock against `chainTimeout`. If
 * `elapsedMs < chainTimeout`, the cancellation couldn't have originated from the
 * inner timer — therefore an outer scope fired it. Rethrow.
 *
 * **Test strategy**: both tests force the outer cancellation from a CALLER-SIDE
 * `withTimeout` (above `executeChainsInBatch`). This route bypasses the
 * `executeChainsInBatch` time-slicing guard ("Insufficient time remaining"), which
 * silently aborts the loop before any chain runs and made an earlier version of this
 * test produce false positives. The caller-side TCE behaves identically to the
 * batch-level TCE for the purposes of the inner catch — both arrive as a TCE from a
 * scope outside `executeChain`'s inner `withTimeout(chainTimeout)` block.
 */
class V250NestedTimeoutMisattributionTest {

    private fun makeTempDir(): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_nestedtimeout_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir()
    )

    /**
     * Worker that suspends in a cancellation-cooperative `delay` longer than the
     * outer timeout, but well under chainTimeout. Lets the test force the
     * outermost cancellation to fire while we're inside the inner
     * `withTimeout(chainTimeout)` block.
     */
    private val slowWorkerFactory = object : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
            override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                // 30 s — chainTimeout for APP_REFRESH is 25 s, but the OUTER timer we
                // wrap this in fires far sooner (≈ 1500 ms below).
                delay(30_000)
                return WorkerResult.Success()
            }
        }
    }

    @Test
    fun outerTimeout_propagatesUpwards_andDoesNotMisattributeAsChainTimeout(): Unit = runBlocking {
        val storage = makeStorage()
        val executor = ChainExecutor(
            workerFactory = slowWorkerFactory,
            taskType = BGTaskType.APP_REFRESH,  // chainTimeout = 25 s
            fileStorage = storage
        )
        try {
            executor.resetShutdownState()

            val chainId = "outer-timeout-chain"
            storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest("SlowWorker"))))
            storage.enqueueChain(chainId)

            // Caller-side outer timeout. Use a generous internal batch totalTimeoutMs so
            // the time-slicing guard doesn't short-circuit before any chain runs. The
            // CALLER's withTimeout(1500) is what actually fires first — it's the
            // outermost cancellation source, and exactly the shape the bug describes
            // (an outer scope's TCE reaches the inner catch).
            assertFailsWith<TimeoutCancellationException>(
                "REGRESSION: caller-side withTimeout cancellation was swallowed inside " +
                    "the chain executor's nested catch. Pre-fix, executeChain's inner " +
                    "`catch (TimeoutCancellationException)` mis-attributed any TCE — " +
                    "including those from outer scopes — as a chain-level timeout, set " +
                    "ExecutionStatus.TIMEOUT, and `return false` instead of rethrowing. " +
                    "The outer scope therefore never observed its own cancellation. The " +
                    "fix detects elapsed < chainTimeout and rethrows."
            ) {
                withTimeout(1500L) {
                    withContext(Dispatchers.Default) {
                        executor.executeChainsInBatch(
                            maxChains = 1,
                            // Large enough to pass the time-slicing guard
                            // (minTimePerChain = min(taskTimeout=20s, conservativeTimeout))
                            totalTimeoutMs = 60_000L
                        )
                    }
                }
            }

            // Flush so loadChainProgress sees what the executor saved during cancellation.
            storage.flushNow()

            // Second half of the contract: the chain progress that *was* persisted
            // during cancellation handling must NOT carry the misleading "Chain timed
            // out (chainTimeoutMs)" lastError. Pre-fix, this was the symptom that
            // poisoned diagnostics and history records — a clearly-not-chain-timeout
            // event was logged with the wrong attribution.
            val saved = storage.loadChainProgress(chainId)
            val lastError = saved?.lastError
            assertFalse(
                lastError != null && lastError.contains("Chain timed out (25000ms)"),
                "REGRESSION: saved progress.lastError = '$lastError'. The chain ran for " +
                    "far less than chainTimeout (25 s); the cancellation came from an " +
                    "outer scope. The inner catch must NOT label this as a chain timeout."
            )
        } finally {
            executor.close()
            storage.close()
        }
    }
}
