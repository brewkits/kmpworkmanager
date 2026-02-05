package dev.brewkits.kmpworkmanager.background.domain

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * Android-specific diagnostics implementation
 * v2.2.2+ feature for debugging task execution
 *
 * **Android-specific health checks:**
 * - Doze mode detection (affects WorkManager scheduling)
 * - Battery via BatteryManager
 * - Storage via StatFs
 * - Network via ConnectivityManager
 * - WorkManager queue inspection
 */
class AndroidWorkerDiagnostics(
    private val context: Context
) : WorkerDiagnostics {

    private val workManager = WorkManager.getInstance(context)

    override suspend fun getSchedulerStatus(): SchedulerStatus {
        // Get all pending/running work
        val workInfos = workManager.getWorkInfosByTag("kmp-worker").get()
        val pendingTasks = workInfos.count {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }

        return SchedulerStatus(
            isReady = true,
            totalPendingTasks = pendingTasks,
            queueSize = workInfos.size,
            platform = "android",
            timestamp = System.currentTimeMillis()
        )
    }

    override suspend fun getSystemHealth(): SystemHealthReport {
        val batteryStatus = getBatteryStatus()
        val storageInfo = getStorageInfo()
        val networkAvailable = isNetworkAvailable()
        val dozeMode = isDeviceInDozeMode()

        return SystemHealthReport(
            timestamp = System.currentTimeMillis(),
            batteryLevel = batteryStatus.first,
            isCharging = batteryStatus.second,
            networkAvailable = networkAvailable,
            storageAvailable = storageInfo.first,
            isStorageLow = storageInfo.second,
            isLowPowerMode = false, // iOS only
            deviceInDozeMode = dozeMode
        )
    }

    override suspend fun getTaskStatus(id: String): TaskStatusDetail? {
        val workInfo = try {
            workManager.getWorkInfoById(java.util.UUID.fromString(id)).get()
        } catch (e: Exception) {
            return null
        } ?: return null // WorkInfo is null

        return TaskStatusDetail(
            taskId = id,
            workerClassName = workInfo.tags.firstOrNull { it.startsWith("worker:") }
                ?.removePrefix("worker:") ?: "Unknown",
            state = when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> "PENDING"
                WorkInfo.State.RUNNING -> "RUNNING"
                WorkInfo.State.SUCCEEDED -> "COMPLETED"
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> "FAILED"
                WorkInfo.State.BLOCKED -> "BLOCKED"
            },
            retryCount = workInfo.runAttemptCount,
            lastExecutionTime = null, // Not directly available from WorkInfo
            lastError = workInfo.outputData.getString("error")
        )
    }

    /**
     * Get battery level and charging status
     * @return Pair(batteryLevel 0-100, isCharging)
     */
    private fun getBatteryStatus(): Pair<Int, Boolean> {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            0
        }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    /**
     * Get storage info
     * @return Pair(availableBytes, isLow <500MB)
     */
    private fun getStorageInfo(): Pair<Long, Boolean> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val availableBytes = stat.availableBytes
        val isLow = availableBytes < 500_000_000L // <500MB

        return Pair(availableBytes, isLow)
    }

    /**
     * Check network availability
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val activeNetwork = connectivityManager.activeNetworkInfo
            activeNetwork?.isConnected == true
        }
    }

    /**
     * Check if device is in doze mode
     * Doze mode affects WorkManager scheduling
     */
    private fun isDeviceInDozeMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isDeviceIdleMode == true
        } else {
            false
        }
    }
}
