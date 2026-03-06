@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import platform.Foundation.*

/**
 * Shared file coordination utility for iOS (v2.3.5+)
 * Ensures inter-process safety between App and App Extensions.
 */
internal object IosFileCoordinator {

    private val fileCoordinator = NSFileCoordinator(filePresenter = null)

    // Sentinel to distinguish "callback not called" from "callback returned null"
    private object UNSET

    /**
     * Executes a block with NSFileCoordinator protection.
     * Detects test environment to avoid hangs during unit tests.
     */
    fun <T> coordinate(
        url: NSURL,
        write: Boolean,
        isTestMode: Boolean = false,
        timeoutMs: Long = 30_000L,
        block: (NSURL) -> T
    ): T {
        // Detect test environment if not explicitly provided
        val isTestEnvironment = isTestMode || when {
            NSProcessInfo.processInfo.environment.containsKey("KMPWORKMANAGER_TEST_MODE") -> {
                val value = NSProcessInfo.processInfo.environment["KMPWORKMANAGER_TEST_MODE"] as? String
                value == "1" || value?.equals("true", ignoreCase = true) == true
            }
            else -> {
                val processName = NSProcessInfo.processInfo.processName
                processName.contains("test.kexe") || processName.contains("Test")
            }
        }

        if (isTestEnvironment) {
            Logger.v(LogTags.CHAIN, "Test mode detected - skipping NSFileCoordinator for ${url.lastPathComponent}")
            return block(url)
        }

        var result: Any? = UNSET
        var blockError: Exception? = null
        val startTime = (NSDate().timeIntervalSince1970 * 1000).toLong()

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
                throw IllegalStateException("File coordination failed for ${url.path}: ${error.localizedDescription}")
            }
        }

        val duration = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTime
        if (timeoutMs > 0 && duration > timeoutMs) {
            Logger.w(
                LogTags.CHAIN,
                "⚠️ File coordination took ${duration}ms (threshold: ${timeoutMs}ms) for ${url.lastPathComponent}"
            )
        }

        blockError?.let { throw it }
        if (result === UNSET) throw IllegalStateException("File coordination callback did not execute for ${url.path}")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
