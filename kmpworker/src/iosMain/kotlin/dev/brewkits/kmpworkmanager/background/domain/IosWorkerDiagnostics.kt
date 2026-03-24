package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlinx.cinterop.*

/**
 * iOS-specific diagnostics implementation
 * feature for debugging task execution
 *
 * **iOS-specific health checks:**
 * - Low power mode detection (affects BGTask scheduling)
 * - Battery state via UIDevice
 * - Storage via NSFileManager
 * - Network reachability
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosWorkerDiagnostics(
    private val fileStorage: IosFileStorage
) : WorkerDiagnostics {

    override suspend fun getSchedulerStatus(): SchedulerStatus {
        val queueSize = fileStorage.getQueueSize()

        return SchedulerStatus(
            isReady = true, // File storage is always ready
            totalPendingTasks = queueSize,
            queueSize = queueSize,
            platform = "ios",
            timestamp = nowMillis()
        )
    }

    override suspend fun getSystemHealth(): SystemHealthReport {
        // Enable battery monitoring temporarily
        UIDevice.currentDevice.batteryMonitoringEnabled = true

        val batteryLevel = UIDevice.currentDevice.batteryLevel // 0.0-1.0
        val batteryState = UIDevice.currentDevice.batteryState
        val isCharging = batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                        batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull

        // Low power mode detection via NSProcessInfo (available iOS 9+)
        @Suppress("UNCHECKED_CAST")
        val isLowPowerMode = NSProcessInfo.processInfo.performSelector(
            NSSelectorFromString("isLowPowerModeEnabled")
        ) as? Boolean ?: false

        // Storage check
        val freeSpace = fileStorage.getAvailableDiskSpace()
        val isStorageLow = freeSpace < 500_000_000L // <500MB

        // Network check: conservatively returns true (NWPathMonitor requires Swift interop)
        val networkAvailable = checkNetworkReachability()

        // Disable battery monitoring to save power
        UIDevice.currentDevice.batteryMonitoringEnabled = false

        return SystemHealthReport(
            timestamp = nowMillis(),
            batteryLevel = (batteryLevel * 100).toInt().coerceIn(0, 100),
            isCharging = isCharging,
            networkAvailable = networkAvailable,
            storageAvailable = freeSpace,
            isStorageLow = isStorageLow,
            isLowPowerMode = isLowPowerMode,
            deviceInDozeMode = false // Android only
        )
    }

    override suspend fun getTaskStatus(id: String): TaskStatusDetail? {
        // Check if chain exists via chain definition or one-time task metadata
        val chainDefinition = fileStorage.loadChainDefinition(id)
        val oneTimeMetadata = if (chainDefinition == null) fileStorage.loadTaskMetadata(id, periodic = false) else null

        if (chainDefinition == null && oneTimeMetadata == null) {
            // Also check periodic metadata
            val periodicMetadata = fileStorage.loadTaskMetadata(id, periodic = true)
                ?: return null

            val workerClass = periodicMetadata["workerClassName"] ?: "Unknown"
            return TaskStatusDetail(
                taskId = id,
                workerClassName = workerClass,
                state = "PENDING",
                retryCount = 0,
                lastExecutionTime = null,
                lastError = null
            )
        }

        // Load chain progress if exists
        val progress = fileStorage.loadChainProgress(id)

        // Derive worker class name: first task of first step in chain, or one-time task metadata
        val workerClassName = chainDefinition?.firstOrNull()?.firstOrNull()?.workerClassName
            ?: oneTimeMetadata?.get("workerClassName")
            ?: "Unknown"

        return TaskStatusDetail(
            taskId = id,
            workerClassName = workerClassName,
            state = when {
                progress == null -> "PENDING"
                progress.isComplete() -> "COMPLETED"
                progress.hasExceededRetries() -> "FAILED"
                else -> "RUNNING"
            },
            retryCount = progress?.retryCount ?: 0,
            lastExecutionTime = null,
            lastError = progress?.lastError
        )
    }

    /**
     * Network reachability check.
     * Returns true conservatively — NWPathMonitor requires Swift interop to implement properly.
     * For accurate network state, pass the result from Swift via your app's bridge layer.
     */
    private fun checkNetworkReachability(): Boolean = true

    private fun nowMillis(): Long {
        return (NSDate().timeIntervalSince1970 * 1000).toLong()
    }
}

/**
 * Returns available disk space in bytes using NSFileManager.
 * Falls back to 0 (conservative — signals low storage) on any error.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun IosFileStorage.getAvailableDiskSpace(): Long {
    return try {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(
            path = "/",
            error = null
        )
        (attrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longValue ?: 0L
    } catch (e: Exception) {
        0L
    }
}
