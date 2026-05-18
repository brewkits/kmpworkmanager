@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.FileCoordinationTimeoutException
import dev.brewkits.kmpworkmanager.background.data.IosDispatchers
import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P1 bug: when `NSFileCoordinator` could not acquire a
 * lock within `timeoutMs`, [IosFileCoordinator.coordinateBlocking] used to **bypass**
 * coordination — `return block(url)` — and execute the I/O without the lock. The
 * justification in code was "safer than hanging indefinitely (Watchdog)", but on iOS:
 *
 *  - The App and App Extension (Share, Widget, BGProcessing) coordinate on the same
 *    `queue.jsonl` / event-store files.
 *  - The file format is binary with framed records + CRC32. Two unlocked writers ⇒
 *    bytes interleave ⇒ records become valid bytes that fail CRC ⇒ silent data loss.
 *  - `coordinateBlocking` already runs off the main thread (via `IosDispatchers.IO`),
 *    so Watchdog isn't actually at risk — there was no genuine trade-off.
 *
 * The fix throws [FileCoordinationTimeoutException]; the caller maps to a retry.
 *
 * **Why we call `coordinateBlocking` directly**: [IosFileCoordinator.coordinate]
 * short-circuits in test environments (see [dev.brewkits.kmpworkmanager.utils.IosTestEnvironment])
 * so a test could never hit the timeout branch through the public API. The blocking
 * helper is marked `internal` for exactly this reason.
 *
 * **Why `timeoutMs = 0`**: forcing real `NSFileCoordinator` lock contention in the
 * simulator-test sandbox is unreliable (the system refuses to coordinate on
 * non-container URLs with "couldn't be saved" errors). Passing `timeoutMs = 0` makes
 * `dispatch_semaphore_wait(NOW)` return immediately as timed-out — the dispatched
 * coordinator block hasn't yet had a chance to run, `isCompleted` is still 0, and the
 * outer `compareAndSet(0, 1)` wins → throws. This deterministically exercises the
 * exact branch the fix lives in.
 */
class V250FileCoordinatorTimeoutTest {

    private val filePath = NSTemporaryDirectory() + "kmp_coord_timeout_" +
        NSDate().timeIntervalSince1970.toLong() + ".bin"
    private val fileURL = NSURL.fileURLWithPath(filePath)

    @BeforeTest
    fun setUp() {
        (("seed" as NSString)).writeToURL(fileURL, true, NSUTF8StringEncoding, null)
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(filePath, null)
    }

    @Test
    fun timeout_throwsException_andDoesNotInvokeBlockWithoutLock() = runBlocking {
        val blockRan = kotlin.concurrent.AtomicInt(0)

        val ex = assertFailsWith<FileCoordinationTimeoutException> {
            withContext(IosDispatchers.IO) {
                IosFileCoordinator.coordinateBlocking<Unit>(
                    url = fileURL,
                    write = true,
                    timeoutMs = 0L,
                    block = {
                        // If this runs, the bypass-on-timeout bug is back. Recording the
                        // value rather than calling fail() so the test can produce a
                        // diagnostic message even from inside the GCD callback thread.
                        blockRan.value = 1
                    }
                )
            }
        }

        assertEquals(
            0, blockRan.value,
            "REGRESSION: block ran during a timeout. The FileCoordinator bypass bug is " +
                "back — App↔Extension contention will now corrupt queue.jsonl (interleaved " +
                "binary writes ⇒ CRC32 fail ⇒ silent record loss). The fix in " +
                "IosFileCoordinator.coordinateBlocking() must throw on timeout, not " +
                "`return block(url)`."
        )
        assertTrue(
            ex.message!!.contains("timed out"),
            "exception message must mention timeout for caller diagnostics, was: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains(filePath) || ex.message!!.contains(fileURL.path ?: ""),
            "exception message must include the contended file path, was: ${ex.message}"
        )
    }
}
