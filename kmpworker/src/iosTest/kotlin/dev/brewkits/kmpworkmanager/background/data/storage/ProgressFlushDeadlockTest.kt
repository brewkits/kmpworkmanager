@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hunts a suspected deadlock in [ChainProgressStore]'s flush coordination.
 *
 * ### The hypothesis
 *
 * `flushProgressBuffer` publishes a `CompletableDeferred` in `flushCompletionSignal` so that
 * concurrent `flushNow()` callers can await the in-flight flush instead of starting a second
 * one. It completes that signal in a `finally`:
 *
 * ```
 * } finally {
 *     progressMutex.withLock { flushCompletionSignal = null; ... }   // suspend
 *     completionSignal.complete(Unit)                               // never reached if the above throws
 * }
 * ```
 *
 * `Mutex.withLock` is a suspending call. Its fast path takes an uncontended mutex without
 * suspending — and therefore without checking for cancellation — but when the mutex is
 * *contended* it must suspend, and suspending inside a cancelled coroutine throws
 * `CancellationException`. If that happens, `completionSignal.complete(Unit)` never runs and
 * `flushCompletionSignal` is never cleared.
 *
 * `flushNow()` then does exactly the wrong thing with the corpse:
 *
 * ```
 * flushJob?.cancelAndJoin()                                   // can be what cancels the flush
 * val signal = progressMutex.withLock { flushCompletionSignal }
 * signal?.await()                                             // waits forever on a signal nobody will complete
 * ```
 *
 * So the sequence is: a debounced flush is running; something calls `flushNow()`, whose
 * `cancelAndJoin()` cancels it; the flush's `finally` hits a contended mutex (a concurrent
 * `saveChainProgress` holds it) and dies without completing the signal; `flushNow()` picks up
 * that signal and blocks forever.
 *
 * That matters because `flushNow()` is what `IosFileStorage.close()` and chain completion
 * call — a hang there is an app that never shuts down and progress that is never persisted.
 *
 * ### How this test works
 *
 * The private state is not reachable, so the race is provoked rather than stepped through:
 * many concurrent `saveChainProgress` calls create mutex contention while repeated
 * `flushNow()` calls cancel the debounced flush underneath them. Everything runs inside
 * `withTimeout`, so a deadlock surfaces as a failure instead of a hung suite.
 */
class ProgressFlushDeadlockTest {

    private lateinit var root: NSURL
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        root = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}FlushDeadlock-$stamp-${platform.posix.rand()}"
        )
        NSFileManager.defaultManager.createDirectoryAtURL(
            root, withIntermediateDirectories = true, attributes = null, error = null
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtURL(root, error = null)
    }

    private fun newStore() = ChainProgressStore(
        io = StorageFileIo(isTestMode = true, coordinatorTestMode = true, coordinationTimeoutMs = 30_000L),
        backgroundScope = scope,
        persistenceJson = Json { ignoreUnknownKeys = true },
        chainsDir = { root }
    )

    /**
     * The mechanism, in isolation and with no dependency on this project's code: a contended
     * `Mutex.withLock` inside a cancelled coroutine's `finally` throws, so any statement after
     * it is skipped. If this ever stops being true the analysis above no longer applies.
     */
    @Test
    fun contendedWithLockInACancelledFinallyThrowsAndSkipsWhatFollows() = runTest {
        val mutex = Mutex()
        var reachedStatementAfterWithLock = false
        val holderHasIt = kotlinx.coroutines.CompletableDeferred<Unit>()
        val victimIsInFinally = kotlinx.coroutines.CompletableDeferred<Unit>()

        withContext(Dispatchers.Default) {
            // Hold the mutex for the whole test so the victim's withLock must suspend.
            val holder = launch {
                mutex.withLock {
                    holderHasIt.complete(Unit)
                    kotlinx.coroutines.delay(5_000)
                }
            }
            holderHasIt.await()

            val victim = launch {
                try {
                    victimIsInFinally.complete(Unit)
                    kotlinx.coroutines.delay(10_000)
                } finally {
                    mutex.withLock { /* contended: must suspend */ }
                    reachedStatementAfterWithLock = true
                }
            }
            victimIsInFinally.await()
            yield()
            victim.cancel()
            kotlinx.coroutines.joinAll(victim)
            holder.cancel()
        }

        assertTrue(
            !reachedStatementAfterWithLock,
            "If a contended withLock in a cancelled finally no longer throws, ChainProgressStore's " +
                "flush-signal analysis needs revisiting — the deadlock this test hunts would be gone."
        )
    }

    /**
     * The end-to-end race. A deadlock shows up as this test timing out rather than the whole
     * suite hanging.
     */
    @Test
    fun flushNowNeverBlocksForeverWhenItCancelsAnInFlightFlush() = runTest {
        val store = newStore()

        withContext(Dispatchers.Default) {
            withTimeout(60_000) {
                repeat(25) { round ->
                    // A large buffer makes flushProgressBuffer's write loop long, so a
                    // cancellation lands *inside* the try block rather than after it —
                    // that is the only case where the finally's withLock matters.
                    (0 until 400).forEach { i ->
                        store.saveChainProgress(
                            ChainProgress(chainId = "chain-$round-$i", totalSteps = 1)
                        )
                    }

                    // Hammer the mutex for the whole window so the finally finds it
                    // contended and has to suspend.
                    val contenders = (0 until 6).map { i ->
                        async {
                            repeat(300) { step ->
                                store.saveChainProgress(
                                    ChainProgress(chainId = "hot-$round-$i", totalSteps = 300)
                                )
                            }
                        }
                    }

                    // Let the debounced job start the flush, then cancel it out from under.
                    kotlinx.coroutines.delay(FLUSH_DEBOUNCE_APPROX_MS + 20)
                    val flushers = (0 until 3).map { async { store.flushNow() } }

                    contenders.awaitAll()
                    flushers.awaitAll()
                }
            }
        }
    }

    private companion object {
        // ChainProgressStore.FLUSH_DEBOUNCE_MS is private; this straddles it so some rounds
        // cancel a flush that has started and some cancel one that has not.
        const val FLUSH_DEBOUNCE_APPROX_MS = 100L
    }
}
