@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.InsufficientDiskSpaceException
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Owns the two housekeeping concerns that are not about any single kind of record: the
 * disk-space guard that gates writes, and the periodic maintenance run that reaps stale
 * files and records when it last happened.
 *
 * Stage 4 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`), extracted
 * before `ChainDefinitionStore` because that store's `saveChainDefinition` calls
 * [checkDiskSpace] and would otherwise have to reach back into the façade for it.
 *
 * A verbatim move, with one shape change: [runMaintenance] takes the cleanup actions as a
 * parameter instead of calling `cleanupStaleDeletedMarkers` and `cleanupStaleMetadata`
 * directly. Those now live in two different stores, and passing them in keeps the wiring
 * explicit and free of construction-order constraints — this class does not need to know
 * which stores exist, only that something wants reaping.
 *
 * @param io the shared file-I/O + coordination primitives.
 * @param baseDir the storage root, whose filesystem is queried for free space.
 * @param maintenanceTimestampFile where the last-run timestamp is recorded.
 * @param diskSpaceBufferBytes headroom demanded on top of the requested size.
 * @param isTestMode governs write atomicity, matching the rest of the storage layer.
 */
internal class StorageMaintenance(
    private val io: StorageFileIo,
    private val baseDir: () -> NSURL,
    private val maintenanceTimestampFile: () -> NSURL,
    private val diskSpaceBufferBytes: Long,
    private val isTestMode: Boolean
) {

    // Disk space cache — `attributesOfFileSystemForPath` is an OS-level I/O syscall.
    // Calling it on every file write (e.g. every saveChainDefinition) adds measurable
    // latency on I/O-bound devices. Cache the result for DISK_SPACE_CACHE_TTL_MS and
    // re-query only when the TTL expires.
    private var diskSpaceCacheFreeBytes: Long = -1L
    private var diskSpaceCacheExpiryMs: Long = 0L

    /**
     * Check if sufficient disk space is available.
     *
     * **Safety margin:** Requires configurable buffer (default 50MB) + actual size to prevent
     * system-wide issues and ensure smooth operation.
     *
     * @param requiredBytes Minimum bytes needed for the operation
     * @throws InsufficientDiskSpaceException if space unavailable
     */
    fun checkDiskSpace(requiredBytes: Long) {
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Use cached free-space value if still fresh (avoids attributesOfFileSystemForPath
        // syscall on every file write — stale reads within the TTL are intentional and safe).
        val freeSpace: Long = if (nowMs < diskSpaceCacheExpiryMs && diskSpaceCacheFreeBytes >= 0L) {
            Logger.v(
                LogTags.CHAIN,
                "Disk space cache hit: ${diskSpaceCacheFreeBytes / 1024 / 1024}MB free"
            )
            diskSpaceCacheFreeBytes
        } else {
            // Cache miss — query the filesystem and refresh the cache.
            val basePath = baseDir().path ?: run {
                Logger.w(
                    LogTags.CHAIN,
                    "Cannot read filesystem attributes — baseDir has no path, " +
                        "skipping disk space check"
                )
                return
            }
            val fresh = memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val attributes = io.fileManager.attributesOfFileSystemForPath(
                    basePath,
                    error = errorPtr.ptr
                ) as? Map<*, *>

                if (attributes == null) {
                    Logger.w(
                        LogTags.CHAIN,
                        "Cannot read filesystem attributes - skipping disk space check"
                    )
                    return
                }
                (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L
            }
            // Update cache (Volatile writes are immediately visible to other threads)
            diskSpaceCacheFreeBytes = fresh
            diskSpaceCacheExpiryMs = nowMs + DISK_SPACE_CACHE_TTL_MS
            Logger.d(
                LogTags.CHAIN,
                "Disk space cache refreshed: ${fresh / 1024 / 1024}MB free " +
                    "(TTL ${DISK_SPACE_CACHE_TTL_MS / 1000}s)"
            )
            fresh
        }

        val requiredWithBuffer = requiredBytes + diskSpaceBufferBytes

        if (freeSpace < requiredWithBuffer) {
            val freeMB = freeSpace / 1024 / 1024
            val requiredMB = requiredWithBuffer / 1024 / 1024
            val bufferMB = diskSpaceBufferBytes / 1024 / 1024

            Logger.e(
                LogTags.CHAIN,
                "Insufficient disk space: ${freeMB}MB available, " +
                    "${requiredMB}MB required (${bufferMB}MB buffer)"
            )
            throw InsufficientDiskSpaceException(requiredWithBuffer, freeSpace)
        }

        Logger.d(
            LogTags.CHAIN,
            "Disk space OK: ${freeSpace / 1024 / 1024}MB available, " +
                "${requiredWithBuffer / 1024 / 1024}MB required"
        )
    }

    /**
     * Run the given [cleanups], then record that maintenance happened.
     *
     * Swallows failures on purpose: maintenance is opportunistic housekeeping launched from
     * an init block, and a reaper that throws must not take the storage instance — or the app
     * launch — down with it. The completion timestamp is only written if every cleanup
     * returned, so a failed run stays "overdue" and is retried rather than being recorded as
     * done.
     */
    @Suppress("TooGenericExceptionCaught")
    fun runMaintenance(cleanups: List<() -> Unit>) {
        try {
            Logger.d(LogTags.CHAIN, "Starting maintenance tasks...")

            cleanups.forEach { it() }

            recordMaintenanceCompletion()

            Logger.d(LogTags.CHAIN, "Maintenance tasks completed")
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Maintenance tasks failed", e)
        }
    }

    /**
     * Returns true when maintenance is overdue based on the given hour interval.
     *
     * Exposed for testing — allows tests to verify the skip-if-recent guard without
     * waiting for the actual 24h window.
     *
     * @param hoursInterval The interval in hours. A value of 0 always returns true.
     */
    fun isMaintenanceRequired(hoursInterval: Int): Boolean {
        if (hoursInterval == 0) return true
        return hoursSinceLastMaintenance() >= hoursInterval
    }

    /**
     * Get hours since last maintenance run.
     *
     * @return Hours since last maintenance, or Int.MAX_VALUE if never run
     */
    fun hoursSinceLastMaintenance(): Int {
        val path = maintenanceTimestampFile().path ?: return Int.MAX_VALUE

        if (!io.fileManager.fileExistsAtPath(path)) {
            return Int.MAX_VALUE // Never run before
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )

            val lastRunTimestamp = content?.toString()?.trim()?.toLongOrNull()
                ?: return Int.MAX_VALUE
            val currentTimestamp = NSDate().timeIntervalSince1970.toLong()
            val hoursSince = (currentTimestamp - lastRunTimestamp) / 3600

            hoursSince.toInt()
        }
    }

    /** Record maintenance completion timestamp. */
    private fun recordMaintenanceCompletion() {
        val path = maintenanceTimestampFile().path ?: return
        val timestamp = NSDate().timeIntervalSince1970.toLong()

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val content = timestamp.toString() as NSString

            content.writeToFile(
                path,
                atomically = !isTestMode,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
        }
    }

    private companion object {
        private const val DISK_SPACE_CACHE_TTL_MS = 10_000L // 10 seconds
    }
}
