package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlinx.cinterop.*

/**
 * iOS-specific diagnostics implementation
 * v2.2.2+ feature for debugging task execution
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
        val isLowPowerMode = NSProcessInfo.processInfo.lowPowerModeEnabled

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
        // Check if chain exists in queue
        val chainExists = fileStorage.chainIdExists(id)

        if (!chainExists) {
            return null
        }

        // Load chain progress if exists
        val progress = fileStorage.loadChainProgress(id)

        return TaskStatusDetail(
            taskId = id,
            workerClassName = "ChainWorker", // iOS uses chains, not individual workers
            state = when {
                progress == null -> "PENDING"
                progress.getCompletionPercentage() == 100 -> "COMPLETED"
                progress.hasExceededRetries() -> "FAILED"
                else -> "RUNNING"
            },
            retryCount = progress?.retryCount ?: 0,
            lastExecutionTime = null, // Not tracked in current implementation
            lastError = null // Not tracked in current implementation
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
 * Extension to check if chain ID exists in queue
 * (Assuming IosFileStorage has access to queue)
 */
private suspend fun IosFileStorage.chainIdExists(chainId: String): Boolean {
    // Simple check: try to load chain definition
    return try {
        loadChainDefinition(chainId) != null
    } catch (e: Exception) {
        false
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
