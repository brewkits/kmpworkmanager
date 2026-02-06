package dev.brewkits.kmpworkmanager.background.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Heavy worker that runs in foreground service with persistent notification.
 * Used for long-running tasks (>10 minutes) or CPU-intensive work.
 *
 * **When to use:**
 * - CPU-intensive tasks (video processing, encryption, large file operations)
 * - Tasks that may take > 10 minutes
 * - Tasks that should not be interrupted by system doze mode
 *
 * **How to use:**
 * Set `Constraints(isHeavyTask = true)` when scheduling:
 * ```kotlin
 * scheduler.enqueue(
 *     id = "heavy-processing",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "ProcessVideoWorker",
 *     constraints = Constraints(isHeavyTask = true) // ← Use KmpHeavyWorker
 * )
 * ```
 *
 * ```kotlin
 * val inputData = buildJsonObject {
 *     put(KmpHeavyWorker.NOTIFICATION_TITLE_KEY, "處理中")
 *     put(KmpHeavyWorker.NOTIFICATION_TEXT_KEY, "正在處理大型任務...")
 * }.toString()
 *
 * scheduler.enqueue(
 *     id = "heavy-processing",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "ProcessVideoWorker",
 *     constraints = Constraints(isHeavyTask = true),
 *     inputJson = inputData  // ← Custom notification text
 * )
 * ```
 *
 *
 * Android 14+ requires explicit foregroundServiceType declaration. The default is DATA_SYNC,
 * but if your app uses location tracking, media playback, or camera, you MUST specify the
 * correct type to avoid SecurityException crashes.
 *
 * **Example 1: Location Tracking**
 * ```kotlin
 * val inputData = buildJsonObject {
 *     put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
 *         android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
 * }.toString()
 *
 * scheduler.enqueue(
 *     id = "location-tracking",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "LocationWorker",
 *     constraints = Constraints(isHeavyTask = true),
 *     inputJson = inputData
 * )
 * ```
 *
 * **Example 2: Media Playback**
 * ```kotlin
 * val inputData = buildJsonObject {
 *     put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
 *         android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
 * }.toString()
 *
 * scheduler.enqueue(
 *     id = "audio-processing",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "AudioWorker",
 *     constraints = Constraints(isHeavyTask = true),
 *     inputJson = inputData
 * )
 * ```
 *
 * **Example 3: Multiple Types (Location + Data Sync)**
 * ```kotlin
 * val inputData = buildJsonObject {
 *     put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
 *         android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
 *         android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
 * }.toString()
 * ```
 *
 * **Requirements:**
 * - Requires `FOREGROUND_SERVICE` permission in AndroidManifest.xml
 * - Shows persistent notification while running (Android requirement)
 * - Notification cannot be dismissed until task completes
 *
 * **AndroidManifest.xml (Basic - Data Sync only):**
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <application>
 *     <service
 *         android:name="androidx.work.impl.foreground.SystemForegroundService"
 *         android:foregroundServiceType="dataSync"
 *         tools:node="merge" />
 * </application>
 * ```
 *
 * **AndroidManifest.xml (Location Tracking):**
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
 * <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <application>
 *     <service
 *         android:name="androidx.work.impl.foreground.SystemForegroundService"
 *         android:foregroundServiceType="location|dataSync"
 *         tools:node="merge" />
 * </application>
 * ```
 *
 * **AndroidManifest.xml (Media Playback):**
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <application>
 *     <service
 *         android:name="androidx.work.impl.foreground.SystemForegroundService"
 *         android:foregroundServiceType="mediaPlayback"
 *         tools:node="merge" />
 * </application>
 * ```
 *
 * **v3.0.0+**: Moved to library (previously in composeApp only)
 */
class KmpHeavyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val workerFactory: AndroidWorkerFactory by inject()

    companion object {
        const val CHANNEL_ID = "kmp_heavy_worker_channel"
        const val CHANNEL_NAME = "KMP Heavy Tasks"
        const val NOTIFICATION_ID = 1001

        /**
         * Worker data keys
         */
        const val WORKER_CLASS_KEY = "workerClassName"
        const val INPUT_JSON_KEY = "inputJson"

        /**
         * These can be passed via inputData to customize the foreground notification
         */
        const val NOTIFICATION_TITLE_KEY = "notificationTitle"
        const val NOTIFICATION_TEXT_KEY = "notificationText"

        /**
         * Pass this via inputData to specify the service type when using location/media/camera
         *
         * Example for location tracking:
         * ```kotlin
         * val inputData = buildJsonObject {
         *     put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
         *         ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
         * }.toString()
         * ```
         *
         * Available types (API 34+):
         * - FOREGROUND_SERVICE_TYPE_DATA_SYNC (default)
         * - FOREGROUND_SERVICE_TYPE_LOCATION
         * - FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
         * - FOREGROUND_SERVICE_TYPE_CAMERA
         * - FOREGROUND_SERVICE_TYPE_MICROPHONE
         * - FOREGROUND_SERVICE_TYPE_HEALTH
         * - FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
         * - FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
         * - FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
         */
        const val FOREGROUND_SERVICE_TYPE_KEY = "foregroundServiceType"

        /**
         * Default notification text (used if not provided via inputData)
         */
        private const val DEFAULT_NOTIFICATION_TITLE = "Background Task Running"
        private const val DEFAULT_NOTIFICATION_TEXT = "Processing heavy task..."
    }

    override suspend fun doWork(): Result {
        Logger.i(LogTags.WORKER, "KmpHeavyWorker starting foreground service")

        // 1. Start foreground service with notification
        try {
            setForeground(createForegroundInfo())
        } catch (e: SecurityException) {
            Logger.e(LogTags.WORKER, "Failed to start foreground service - SecurityException", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceType = inputData.getInt(
                    FOREGROUND_SERVICE_TYPE_KEY,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )

                Logger.e(LogTags.WORKER, """
                    Android 14+ Foreground Service Configuration Error

                    Required steps:
                    1. Add to AndroidManifest.xml:
                       <service android:name="androidx.work.impl.foreground.SystemForegroundService"
                                android:foregroundServiceType="${getServiceTypeString(serviceType)}"
                                android:exported="false" />

                    2. Ensure required permissions are granted in manifest:
                       ${getRequiredPermissions(serviceType)}

                    3. Request runtime permissions if needed (for location/camera/microphone)

                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    SDK: ${Build.VERSION.SDK_INT}
                """.trimIndent())
            }

            return Result.failure()
        }

        // 2. Get worker class name and input
        val workerClassName = inputData.getString(WORKER_CLASS_KEY)
        val inputJson = inputData.getString(INPUT_JSON_KEY)

        if (workerClassName == null) {
            Logger.e(LogTags.WORKER, "KmpHeavyWorker missing workerClassName")
            return Result.failure()
        }

        Logger.i(LogTags.WORKER, "Executing heavy worker: $workerClassName")

        // 3. Execute the actual worker
        return try {
            val result = executeHeavyWork(workerClassName, inputJson)

            when (result) {
                is WorkerResult.Success -> {
                    val message = result.message ?: "Heavy worker completed successfully"
                    Logger.i(LogTags.WORKER, "KmpHeavyWorker success: $workerClassName - $message")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = true,
                            message = message,
                            outputData = result.data
                        )
                    )
                    Result.success()
                }
                is WorkerResult.Failure -> {
                    Logger.w(LogTags.WORKER, "KmpHeavyWorker failure: $workerClassName - ${result.message}")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = false,
                            message = result.message,
                            outputData = null
                        )
                    )

                    if (result.shouldRetry) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "KmpHeavyWorker exception: $workerClassName", e)

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName,
                    success = false,
                    message = "Exception: ${e.message}",
                    outputData = null
                )
            )
            Result.failure()
        }
    }

    /**
     * Creates foreground notification info
     *
     * **Android 14+ Requirement:**
     * The foregroundServiceType must match the type declared in AndroidManifest.xml:
     * ```xml
     * <service android:name="androidx.work.impl.foreground.SystemForegroundService"
     *          android:foregroundServiceType="location|dataSync" />
     * ```
     *
     * **How to specify custom type:**
     * ```kotlin
     * val inputData = buildJsonObject {
     *     put(KmpHeavyWorker.FOREGROUND_SERVICE_TYPE_KEY,
     *         ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
     * }.toString()
     *
     * scheduler.enqueue(
     *     id = "location-tracking",
     *     trigger = TaskTrigger.OneTime(),
     *     workerClassName = "LocationWorker",
     *     constraints = Constraints(isHeavyTask = true),
     *     inputJson = inputData
     * )
     * ```
     */
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notificationTitle = inputData.getString(NOTIFICATION_TITLE_KEY) ?: DEFAULT_NOTIFICATION_TITLE
        val notificationText = inputData.getString(NOTIFICATION_TEXT_KEY) ?: DEFAULT_NOTIFICATION_TEXT

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true) // Cannot be dismissed
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for less intrusion
            .build()

        // Now configurable via inputData to support location/media/camera use cases
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: Get service type from inputData or use DATA_SYNC as default
            var serviceType = inputData.getInt(
                FOREGROUND_SERVICE_TYPE_KEY,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )

            try {
                validateForegroundServiceType(serviceType)
                Logger.d(LogTags.WORKER, "Creating ForegroundInfo with serviceType: $serviceType")
            } catch (e: SecurityException) {
                Logger.e(LogTags.WORKER, "Service type validation failed - falling back to DATA_SYNC", e)
                Logger.w(LogTags.WORKER, """
                    Validation failed. Add to AndroidManifest.xml:
                    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
                             android:foregroundServiceType="${getServiceTypeString(serviceType)}" />
                """.trimIndent())

                // Fallback to DATA_SYNC (most permissive type)
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            // API 33 and below: Standard constructor (service type not needed)
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Creates notification channel for foreground service (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance for background work
            ).apply {
                description = "Notifications for long-running background tasks from KMP WorkManager"
                setShowBadge(false) // Don't show badge on app icon
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Executes the actual heavy work by delegating to the specified worker class.
     *
     * v1.0.0+: Now uses AndroidWorkerFactory from Koin
     * v2.3.0+: Returns WorkerResult instead of Boolean
     *
     * @param workerClassName Fully qualified worker class name
     * @param inputJson Optional JSON input data
     * @return WorkerResult indicating success/failure with optional data
     */
    private suspend fun executeHeavyWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)

        if (worker == null) {
            Logger.e(LogTags.WORKER, "Worker factory returned null for heavy worker: $workerClassName")
            return WorkerResult.Failure("Worker not found: $workerClassName")
        }

        return worker.doWork(inputJson)
    }

    /**
     * Validate foreground service type for Android 14+ (API 34+)
     *
     * **FAIL OPEN Philosophy:**
     * This method implements a "fail open" approach - if validation encounters unexpected
     * errors (common on Chinese ROMs like Xiaomi, Oppo, Vivo), it allows the service to
     * proceed rather than crashing. This prevents app crashes while still validating
     * standard Android configurations.
     *
     * **Validation checks:**
     * 1. Service type declared in AndroidManifest.xml
     * 2. Required permissions granted (e.g., ACCESS_FINE_LOCATION for LOCATION type)
     *
     * @param serviceType The requested foreground service type
     * @throws SecurityException if validation fails on standard Android (not on Chinese ROMs)
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun validateForegroundServiceType(serviceType: Int) {
        try {
            val packageManager = applicationContext.packageManager
            val componentName = ComponentName(applicationContext, "androidx.work.impl.foreground.SystemForegroundService")

            // Get service info - may throw NameNotFoundException or RuntimeException on Chinese ROMs
            val serviceInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getServiceInfo(
                        componentName,
                        PackageManager.ComponentInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getServiceInfo(componentName, 0)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Logger.w(LogTags.WORKER, "SystemForegroundService not found in manifest - proceeding anyway (FAIL OPEN)")
                return  // FAIL OPEN
            } catch (e: Exception) {
                // CRITICAL: Chinese ROMs may throw unexpected exceptions
                Logger.e(LogTags.WORKER, "Unexpected ROM behavior during service lookup: ${e.javaClass.simpleName}", e)
                Logger.w(LogTags.WORKER, "Device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK: ${Build.VERSION.SDK_INT}")
                return  // FAIL OPEN
            }

            // Check if service type is declared in manifest
            val declaredTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                serviceInfo.foregroundServiceType
            } else {
                0
            }

            // Verify requested type is included in declared types
            if (declaredTypes != 0 && (declaredTypes and serviceType) == 0) {
                val errorMsg = """
                    Foreground service type mismatch!
                    Requested type: $serviceType
                    Declared types in manifest: $declaredTypes

                    Add to AndroidManifest.xml:
                    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
                             android:foregroundServiceType="${getServiceTypeString(serviceType)}" />
                """.trimIndent()

                Logger.e(LogTags.WORKER, errorMsg)
                throw SecurityException(errorMsg)
            }

            // Check required permissions for specific service types
            when {
                (serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0 -> {
                    if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                        !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        throw SecurityException(
                            "FOREGROUND_SERVICE_TYPE_LOCATION requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission"
                        )
                    }
                }
                (serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0 -> {
                    if (!hasPermission(Manifest.permission.CAMERA)) {
                        throw SecurityException(
                            "FOREGROUND_SERVICE_TYPE_CAMERA requires CAMERA permission"
                        )
                    }
                }
                (serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 -> {
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        throw SecurityException(
                            "FOREGROUND_SERVICE_TYPE_MICROPHONE requires RECORD_AUDIO permission"
                        )
                    }
                }
            }

            Logger.d(LogTags.WORKER, "Foreground service type validation passed: $serviceType")

        } catch (e: SecurityException) {
            // Re-throw expected SecurityException (validation failed)
            throw e
        } catch (e: Exception) {
            // CRITICAL: Catch ALL other exceptions (Chinese ROM edge cases)
            Logger.e(LogTags.WORKER, "Unknown validation error - failing open: ${e.javaClass.simpleName}", e)
            Logger.w(LogTags.WORKER, "Device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK: ${Build.VERSION.SDK_INT}")
            // FAIL OPEN — setForeground() in doWork() remains the authoritative enforcement point;
            // any real misconfiguration will still surface as SecurityException there.
        }
    }

    /**
     * Check if app has a specific permission
     * v2.1.3+
     */
    private fun hasPermission(permission: String): Boolean {
        return applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Convert service type constant to manifest string
     */
    private fun getServiceTypeString(serviceType: Int): String {
        val types = mutableListOf<String>()

        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0) types.add("location")
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0) types.add("camera")
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) types.add("microphone")
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0) types.add("mediaPlayback")
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) != 0) types.add("phoneCall")
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0) types.add("dataSync")

        return types.joinToString("|").ifEmpty { "dataSync" }
    }

    /**
     * Get required permissions for a service type
     */
    private fun getRequiredPermissions(serviceType: Int): String {
        val permissions = mutableListOf<String>()

        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0) {
            permissions.add("<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />")
            permissions.add("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_LOCATION\" />")
        }
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0) {
            permissions.add("<uses-permission android:name=\"android.permission.CAMERA\" />")
            permissions.add("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_CAMERA\" />")
        }
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) {
            permissions.add("<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />")
            permissions.add("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_MICROPHONE\" />")
        }
        if ((serviceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0) {
            permissions.add("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK\" />")
        }

        return if (permissions.isNotEmpty()) {
            permissions.joinToString("\n       ")
        } else {
            "No additional permissions required for DATA_SYNC"
        }
    }
}
