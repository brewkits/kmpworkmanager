@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC

/**
 * Shared file coordination utility for iOS
 * Ensures inter-process safety between App and App Extensions.
 */
internal object IosFileCoordinator {

    private val fileCoordinator = NSFileCoordinator(filePresenter = null)

    // Sentinel to distinguish "callback not called" from "callback returned null"
    private object UNSET

    /**
     * Executes a block with NSFileCoordinator protection.
     *
     * **NSFileCoordinator blocking risk**: `coordinateWritingItemAtURL` / `coordinateReadingItemAtURL`
     * are synchronous blocking calls. They can block the calling thread indefinitely if another
     * process (e.g. iCloud daemon) holds a conflicting coordination lock. There is no native
     * cancellation API on NSFileCoordinator.
     *
     * **Thread safety via [IosDispatchers.IO]**: This function is a `suspend fun` that dispatches
     * all blocking work (including `dispatch_semaphore_wait`) to [IosDispatchers.IO] — a dedicated
     * GCD-backed dispatcher. [kotlinx.coroutines.Dispatchers.Default] threads are NEVER blocked,
     * regardless of how many chains run in parallel. This prevents the thread-starvation scenario
     * where all Default threads are waiting on NSFileCoordinator, freezing all coroutines in the app.
     *
     * **GCD timeout**: The coordinator call is dispatched onto a GCD global queue and joined with a
     * `dispatch_semaphore_wait(timeout)`. If the semaphore times out, we bypass coordination and
     * execute `block` directly. This risks a brief write conflict, but is safer than hanging
     * indefinitely (which would trigger Watchdog).
     *
     * Detects test environment to skip coordination during unit tests (fast path, no IO dispatch).
     */
    suspend fun <T> coordinate(
        url: NSURL,
        write: Boolean,
        isTestMode: Boolean = false,
        timeoutMs: Long = 30_000L,
        block: (NSURL) -> T
    ): T {
        // Detect test environment:
        // Priority 1 — explicit parameter from test setup
        // Priority 2 — env var KMPWORKMANAGER_TEST_MODE=1 set by test runner
        // Priority 3 — process name ends with "test.kexe" (Kotlin/Native test runner)
        // NOTE: We intentionally do NOT use generic "Test" string matching to avoid
        //       bypassing NSFileCoordinator in production apps whose name contains "Test".
        val isTestEnvironment = isTestMode || when {
            NSProcessInfo.processInfo.environment.containsKey("KMPWORKMANAGER_TEST_MODE") -> {
                val value = NSProcessInfo.processInfo.environment["KMPWORKMANAGER_TEST_MODE"] as? String
                value == "1" || value?.equals("true", ignoreCase = true) == true
            }
            else -> NSProcessInfo.processInfo.processName.endsWith("test.kexe")
        }

        if (isTestEnvironment) {
            Logger.v(LogTags.CHAIN, "Test mode detected - skipping NSFileCoordinator for ${url.lastPathComponent}")
            return block(url)
        }

        // Dispatch the blocking NSFileCoordinator call to IosDispatchers.IO.
        // dispatch_semaphore_wait blocks the calling OS thread — this ensures it blocks an IO
        // thread (backed by GCD), never a Dispatchers.Default thread. Dispatchers.Default
        // threads are immediately released to process other coroutines while IO waits.
        return withContext(IosDispatchers.IO) { coordinateBlocking(url, write, timeoutMs, block) }
    }

    /**
     * Executes a block with NSFileCoordinator protection synchronously.
     * Use this when already on a background thread (e.g. IosDispatchers.IO).
     *
     * Applies the same test-environment detection as [coordinate] so that
     * NSFileCoordinator is skipped in unit/simulator tests (avoids "couldn't be saved"
     * errors when the target directory doesn't exist in the sandbox).
     */
    fun <T> coordinateSync(
        url: NSURL,
        write: Boolean,
        timeoutMs: Long = 30_000L,
        block: (NSURL) -> T
    ): T {
        // Mirror the test-mode detection from coordinate() — both must behave identically.
        val isTestEnvironment = when {
            NSProcessInfo.processInfo.environment.containsKey("KMPWORKMANAGER_TEST_MODE") -> {
                val value = NSProcessInfo.processInfo.environment["KMPWORKMANAGER_TEST_MODE"] as? String
                value == "1" || value?.equals("true", ignoreCase = true) == true
            }
            else -> NSProcessInfo.processInfo.processName.endsWith("test.kexe")
        }
        if (isTestEnvironment) {
            Logger.v(LogTags.CHAIN, "Test mode detected - skipping NSFileCoordinator (sync) for ${url.lastPathComponent}")
            return block(url)
        }
        return coordinateBlocking(url, write, timeoutMs, block)
    }

    /**
     * Synchronous implementation of coordination. Always called from [IosDispatchers.IO].
     */
    private fun <T> coordinateBlocking(
        url: NSURL,
        write: Boolean,
        timeoutMs: Long,
        block: (NSURL) -> T
    ): T {
        var result: Any? = UNSET
        var blockError: Exception? = null
        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // AtomicInt flag (0=false, 1=true) ensures the block executes exactly once:
        // if the GCD async block fires late after a timeout fallback has already returned,
        // its compareAndSet fails and the block is a no-op. Native-safe via kotlin.concurrent.
        val isCompleted = kotlin.concurrent.AtomicInt(0)

        // Use a GCD semaphore so the coordinator call has a real thread-level timeout.
        val semaphore = dispatch_semaphore_create(0)
        val queue = dispatch_get_global_queue(0, 0u)

        dispatch_async(queue) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                val byAccessor: (NSURL?) -> Unit = { actualURL ->
                    if (isCompleted.compareAndSet(expected = 0, newValue = 1)) {
                        try {
                            result = block(actualURL ?: url)
                        } catch (e: Exception) {
                            blockError = e
                        }
                    }
                }

                if (write) {
                    fileCoordinator.coordinateWritingItemAtURL(
                        url = url,
                        options = 0u,
                        error = errorPtr.ptr,
                        byAccessor = byAccessor
                    )
                } else {
                    fileCoordinator.coordinateReadingItemAtURL(
                        url = url,
                        options = 0u,
                        error = errorPtr.ptr,
                        byAccessor = byAccessor
                    )
                }

                errorPtr.value?.let { error ->
                    if (blockError == null) {
                        blockError = IllegalStateException(
                            "File coordination failed for ${url.path}: ${error.localizedDescription}"
                        )
                    }
                }
            }
            dispatch_semaphore_signal(semaphore)
        }

        val timeoutNs = dispatch_time(DISPATCH_TIME_NOW, timeoutMs * NSEC_PER_MSEC.toLong())
        val timedOut = dispatch_semaphore_wait(semaphore, timeoutNs) != 0L

        if (timedOut) {
            // Coordination lock was not acquired within timeoutMs. Bypass coordination and
            // execute directly to avoid a permanent hang.
            if (isCompleted.compareAndSet(expected = 0, newValue = 1)) {
                Logger.w(
                    LogTags.CHAIN,
                    "⚠️ NSFileCoordinator timed out after ${timeoutMs}ms for ${url.lastPathComponent} " +
                        "— bypassing coordination. Proceeding without lock."
                )
                fileCoordinator.cancel()
                return block(url)
            }
        }

        val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
        if (duration > timeoutMs / 2) {
            Logger.w(
                LogTags.CHAIN,
                "⚠️ File coordination took ${duration}ms (half-threshold: ${timeoutMs / 2}ms) for ${url.lastPathComponent}"
            )
        }

        blockError?.let { throw it }
        if (result === UNSET) throw IllegalStateException("File coordination callback did not execute for ${url.path}")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
