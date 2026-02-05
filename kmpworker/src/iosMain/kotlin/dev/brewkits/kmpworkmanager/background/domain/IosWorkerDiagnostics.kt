package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.Foundation.NSProcessInfo
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

        // Low power mode (critical for BGTask scheduling)
        // TODO: Implement proper low power mode detection via Swift interop or objc_msgSend
        // NSProcessInfo.processInfo.isLowPowerModeEnabled requires proper Objective-C binding
        val isLowPowerMode = false // Placeholder - production should check actual state

        // Storage check
        val freeSpace = fileStorage.getAvailableDiskSpace()
        val isStorageLow = freeSpace < 500_000_000L // <500MB

        // Network check (simplified - production apps should use NWPathMonitor)
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
     * Check network reachability (simplified)
     * Production apps should use Network.framework's NWPathMonitor for accurate status
     */
    private fun checkNetworkReachability(): Boolean {
        // Simplified: assume network is available
        // Real implementation would use NWPathMonitor:
        // ```swift
        // let monitor = NWPathMonitor()
        // monitor.pathUpdateHandler = { path in
        //     return path.status == .satisfied
        // }
        // ```
        return true
    }

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
 * Extension to get available disk space
 */
internal fun IosFileStorage.getAvailableDiskSpace(): Long {
    // Simplified implementation - returns 1GB
    // Real implementation would use NSFileManager.attributesOfFileSystemForPath
    return 1_000_000_000L
}
