@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 BAD: [IosFileStorage.close] used to wrap *everything*
 * — `flushNow`, `backgroundScope.cancel`, `scopeJob.join` — in a single try/catch.
 * If `flushNow()` threw (full disk, EACCES, NSFileCoordinator failure), the catch
 * swallowed the exception and the `cancel` + `join` lines never ran. The
 * `backgroundScope` (SupervisorJob + Dispatchers.Default) stayed alive forever,
 * leaking debounce / compaction / progress-flush coroutines across the entire
 * process lifetime. In tests it manifested as @AfterTest hangs and "file doesn't
 * exist" crashes from coroutines writing into deleted test directories.
 *
 * The fix splits close() into try { flushNow } finally { cancel + join } so the
 * scope is *always* torn down. This test pins both legs:
 *
 *  1. Happy path: close() cancels the scope.
 *  2. Failure path: close() still cancels the scope when flushNow throws. (We trigger
 *     the throw by deleting the base directory after staging a progress entry — the
 *     next flushProgressBuffer write fails with "no such file or directory" or
 *     similar I/O error.)
 */
class V250CloseFinallyTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_close_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): Pair<IosFileStorage, NSURL> {
        val dir = makeTempDir(tag)
        val storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = dir
        )
        return storage to dir
    }

    @Test
    fun close_happyPath_cancelsBackgroundScope() = runTest {
        val (storage, dir) = makeStorage("happy")
        try {
            assertTrue(storage.isBackgroundScopeActive, "scope must be active before close")
            storage.close()
            assertFalse(
                storage.isBackgroundScopeActive,
                "happy-path close() must cancel the background scope"
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtURL(dir, null)
        }
    }

    /**
     * Forces [IosFileStorage.flushNow] to throw via the `testForceFlushFailure`
     * internal hook — a deterministic stand-in for the real production failure
     * modes (full disk, EACCES, NSFileCoordinator error). Those are nearly
     * impossible to trigger reliably in the simulator-test sandbox, so we inject
     * the synchronous throw directly. What matters structurally is identical:
     * `flushNow()` exits via exception, and the fix's `finally` block must still
     * call `backgroundScope.cancel()`.
     */
    @Test
    fun close_whenFlushThrows_stillCancelsBackgroundScope() = runTest {
        val (storage, dir) = makeStorage("flush-throws")
        try {
            assertTrue(storage.isBackgroundScopeActive, "scope must be active before close")

            storage.testForceFlushFailure = true
            // close() catches the flushNow exception internally and returns normally —
            // the contract under test is the finally-clause side effect on backgroundScope,
            // not exception propagation.
            storage.close()

            assertFalse(
                storage.isBackgroundScopeActive,
                "REGRESSION: close() did NOT cancel backgroundScope when flushNow threw. " +
                    "The pre-fix code wrapped flushNow + cancel + join in a single try/catch, " +
                    "so a flush exception skipped cancel() entirely → coroutine leak. The fix " +
                    "must keep cancel() inside a `finally` block."
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtURL(dir, null)
        }
    }
}
