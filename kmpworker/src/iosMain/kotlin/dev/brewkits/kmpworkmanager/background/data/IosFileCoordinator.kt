@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
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
     * **GCD timeout**: To prevent unbounded blocking, the coordinator call is dispatched
     * onto a GCD global queue and joined with a `dispatch_semaphore_wait(timeout)`. If the semaphore
     * times out, we bypass coordination and execute `block` directly on the calling thread. This
     * risks a brief write conflict with another process, but is safer than hanging indefinitely
     * (which would trigger Watchdog on the Main Thread or consume Dispatchers.Default threads).
     *
     * Detects test environment to skip coordination during unit tests.
     */
    fun <T> coordinate(
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

        var result: Any? = UNSET
        var blockError: Exception? = null
        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Use a GCD semaphore so the coordinator call has a real thread-level timeout.
        // This prevents unbounded blocking if another process holds the coordination lock.
        val semaphore = dispatch_semaphore_create(0)
        val queue = dispatch_get_global_queue(0, 0u)

        dispatch_async(queue) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()

                if (write) {
                    fileCoordinator.coordinateWritingItemAtURL(
                        url = url,
                        options = 0u,
                        error = errorPtr.ptr,
                        byAccessor = { actualURL ->
                            try {
                                result = block(actualURL ?: url)
                            } catch (e: Exception) {
                                blockError = e
                            }
                        }
                    )
                } else {
                    fileCoordinator.coordinateReadingItemAtURL(
                        url = url,
                        options = 0u,
                        error = errorPtr.ptr,
                        byAccessor = { actualURL ->
                            try {
                                result = block(actualURL ?: url)
                            } catch (e: Exception) {
                                blockError = e
                            }
                        }
                    )
                }

                errorPtr.value?.let { error ->
                    blockError = blockError ?: IllegalStateException(
                        "File coordination failed for ${url.path}: ${error.localizedDescription}"
                    )
                }
            }
            dispatch_semaphore_signal(semaphore)
        }

        val timeoutNs = dispatch_time(DISPATCH_TIME_NOW, timeoutMs * NSEC_PER_MSEC.toLong())
        val timedOut = dispatch_semaphore_wait(semaphore, timeoutNs) != 0L

        val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime

        if (timedOut) {
            // Coordination lock was not acquired within timeoutMs. Bypass coordination and
            // execute directly to avoid a permanent hang. This risks a brief write conflict
            // with another process, but is far safer than blocking indefinitely.
            Logger.w(
                LogTags.CHAIN,
                "⚠️ NSFileCoordinator timed out after ${timeoutMs}ms for ${url.lastPathComponent} " +
                    "— bypassing coordination (likely iCloud contention). Proceeding without lock."
            )
            fileCoordinator.cancel()
            return try {
                block(url)
            } catch (e: Exception) {
                throw e
            }
        }

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
