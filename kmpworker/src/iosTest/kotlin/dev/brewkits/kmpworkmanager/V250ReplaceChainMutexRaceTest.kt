@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P0 mutex-bypass race in [IosFileStorage.replaceChainAtomic].
 *
 * **Bug**: `replaceChainAtomic` acquired only `queueMutex` and called
 * `enqueueChainInternal` directly, bypassing `enqueueMutex` entirely. Meanwhile
 * `enqueueChain` acquired `enqueueMutex` (but NOT queueMutex) and called the same
 * `enqueueChainInternal`. Both code paths read the disk-size limit check at line
 * 372 (`val currentSize = queue.getSize()`), and the check was NOT serialised
 * between them. So two callers could both read `currentSize = MAX_QUEUE_SIZE - 1`,
 * both pass the limit check, and both enqueue — driving the queue past its cap.
 *
 * **Pinning the bug**: we widen the check-then-act window via the
 * `testEnqueueInternalDelayMs` test hook. With that window open, two coroutines
 * — one calling `enqueueChain`, one calling `replaceChainAtomic` — are forced to
 * race deterministically.
 *
 *  - **Pre-fix**: replaceChainAtomic doesn't acquire `enqueueMutex`, so it runs
 *    concurrently with the enqueueChain caller through the size check.
 *    Both pass at `size = MAX_QUEUE_SIZE - 1`; queue ends at MAX_QUEUE_SIZE + 1.
 *  - **Post-fix**: replaceChainAtomic acquires `enqueueMutex` around the
 *    `enqueueChainInternal` call. One caller waits, sees `size = MAX_QUEUE_SIZE`,
 *    throws. Queue ends at exactly MAX_QUEUE_SIZE.
 *
 * Filling the queue to MAX_QUEUE_SIZE - 1 in `@BeforeTest` is the slow step
 * (~999 sequential append-only-queue writes); the actual race takes ~150 ms.
 */
class V250ReplaceChainMutexRaceTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_replacerace_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    @Test
    fun concurrentEnqueueAndReplace_doNotBothPassSizeCheck() = runBlocking {
        val storage = makeStorage("race")
        try {
            // Saturate the queue to MAX_QUEUE_SIZE - 1. The very next enqueue is the
            // one that must be serialised — concurrent callers should NOT both pass
            // the size check.
            val maxSize = 1000  // IosFileStorage.MAX_QUEUE_SIZE
            repeat(maxSize - 1) { i ->
                storage.enqueueChain("filler-$i")
            }
            assertEquals(maxSize - 1, storage.getQueueSize(), "setup: queue must be at MAX - 1")

            // Pre-create the chain that replaceChainAtomic will REPLACE, so the
            // delete-then-enqueue path under test is exercised end-to-end.
            storage.saveChainDefinition("victim", listOf(emptyList()))

            // Open the check-then-act window inside enqueueChainInternal.
            // Both racers start their size check at currentSize = MAX - 1.
            // With the fix, only one can be inside the window at a time.
            storage.testEnqueueInternalDelayMs = 200L

            val enqueueException = CompletableDeferred<Throwable?>()
            val replaceException = CompletableDeferred<Throwable?>()

            // Launch both racers on the same Default dispatcher so coroutine
            // dispatch ordering can't accidentally serialise them.
            val enqueueJob = launch(Dispatchers.Default) {
                enqueueException.complete(
                    runCatching { storage.enqueueChain("racer-enqueue") }.exceptionOrNull()
                )
            }
            val replaceJob = launch(Dispatchers.Default) {
                // Tiny stagger so enqueue (which takes enqueueMutex first) is
                // already inside its delay() before replaceChainAtomic starts.
                // Pre-fix: replace then runs lock-free past the size check.
                // Post-fix: replace blocks on enqueueMutex until enqueue completes.
                delay(20)
                replaceException.complete(
                    runCatching {
                        storage.replaceChainAtomic("victim", listOf(emptyList()))
                    }.exceptionOrNull()
                )
            }
            enqueueJob.join()
            replaceJob.join()

            // Restore so close() doesn't deadlock on a pending delay.
            storage.testEnqueueInternalDelayMs = 0L

            val finalSize = storage.getQueueSize()

            // CONTRACT: at MAX - 1 with two racers, the queue must end at exactly
            // MAX. Anything beyond MAX is a race violation (both callers passed the
            // size check concurrently and both enqueued).
            assertTrue(
                finalSize <= maxSize,
                "REGRESSION: queue grew to $finalSize > MAX ($maxSize). " +
                    "replaceChainAtomic + enqueueChain raced through the size check " +
                    "without serialisation. replaceChainAtomic must acquire " +
                    "enqueueMutex around its enqueueChainInternal call so that the " +
                    "check-then-act is observed atomically against concurrent " +
                    "enqueueChain callers."
            )

            // Exactly one of the two racers must have succeeded (queue went from
            // MAX-1 to MAX); the other must have observed the now-full queue and
            // thrown. This double-checks the "limit reached" path stays correct.
            val successes = listOf(enqueueException.await(), replaceException.await())
                .count { it == null }
            assertEquals(
                1, successes,
                "exactly one racer must succeed, the other must throw " +
                    "'Queue size limit exceeded' — got $successes successes. " +
                    "Final size = $finalSize."
            )
        } finally {
            storage.testEnqueueInternalDelayMs = 0L
            storage.close()
        }
    }
}
