
package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.*
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.*
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS implementation of BackgroundTaskScheduler using BGTaskScheduler for background tasks
 * and UNUserNotificationCenter for exact time scheduling (via notifications).
 *
 * Key Features:
 * - BGAppRefreshTask for light tasks (≤30s)
 * - BGProcessingTask for heavy tasks (≤60s)
 * - File-based storage for improved performance and thread safety (v3.0.0+)
 * - Automatic migration from NSUserDefaults (v2.x)
 * - ExistingPolicy support (KEEP/REPLACE)
 * - Task ID validation against Info.plist
 * - Proper error handling with NSError
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeTaskScheduler(
    /**
     * Additional permitted task IDs beyond those in Info.plist.
     *
     * v4.0.0+: Task IDs are now read from Info.plist automatically.
     * This parameter is kept for backward compatibility but is optional.
     *
     * Recommended: Define all task IDs in Info.plist only.
     *
     * Example:
     * ```kotlin
     * val scheduler = NativeTaskScheduler(
     *     additionalPermittedTaskIds = setOf("my-sync-task", "my-upload-task")
     * )
     * ```
     */
    additionalPermittedTaskIds: Set<String> = emptySet()
) : BackgroundTaskScheduler {

    private companion object {
        const val CHAIN_EXECUTOR_IDENTIFIER = "kmp_chain_executor_task"
        const val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0
    }

    private val fileStorage = IosFileStorage()
    private val migration = StorageMigration(fileStorage = fileStorage)

    /**
     * Background scope for IO operations (migration, file access)
     * Uses Dispatchers.Default to avoid blocking Main thread during initialization
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Task IDs read from Info.plist BGTaskSchedulerPermittedIdentifiers
     */
    private val infoPlistTaskIds: Set<String> = InfoPlistReader.readPermittedTaskIds()

    /**
     * Combined set of permitted task IDs (Info.plist + additional)
     * IMPORTANT: Tasks with IDs not in this list will be silently rejected by iOS
     */
    private val permittedTaskIds: Set<String> = infoPlistTaskIds + additionalPermittedTaskIds

    init {
        // Perform one-time migration from NSUserDefaults to file storage
        // Uses background thread to avoid blocking Main thread during app startup
        backgroundScope.launch {
            try {
                val result = migration.migrate()
                if (result.success) {
                    Logger.i(LogTags.SCHEDULER, "Storage migration: ${result.message}")
                } else {
                    Logger.e(LogTags.SCHEDULER, "Storage migration failed: ${result.message}")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Storage migration error", e)
            }
        }

        // Log permitted task IDs for debugging (lightweight, can stay on caller thread)
        Logger.i(LogTags.SCHEDULER, """
            iOS Task ID Configuration:
            - From Info.plist: ${infoPlistTaskIds.joinToString()}
            - Additional: ${additionalPermittedTaskIds.joinToString()}
            - Total permitted: ${permittedTaskIds.size}
        """.trimIndent())
    }

    actual override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Enqueue request - ID: '$id', Trigger: ${trigger::class.simpleName}, Policy: $policy")

        // Validate task ID against Info.plist permitted identifiers
        if (!validateTaskId(id)) {
            Logger.e(LogTags.SCHEDULER, "Task ID '$id' not in Info.plist BGTaskSchedulerPermittedIdentifiers")
            return ScheduleResult.REJECTED_OS_POLICY
        }

        return when (trigger) {
            is TaskTrigger.Periodic -> schedulePeriodicTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.OneTime -> scheduleOneTimeTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Exact -> scheduleExactNotification(id, trigger, workerClassName, inputJson)
            is TaskTrigger.Windowed -> rejectUnsupportedTrigger("Windowed")
            is TaskTrigger.ContentUri -> rejectUnsupportedTrigger("ContentUri")
            TaskTrigger.StorageLow -> rejectUnsupportedTrigger("StorageLow")
            TaskTrigger.BatteryLow -> rejectUnsupportedTrigger("BatteryLow")
            TaskTrigger.BatteryOkay -> rejectUnsupportedTrigger("BatteryOkay")
            TaskTrigger.DeviceIdle -> rejectUnsupportedTrigger("DeviceIdle")
        }
    }

    /**
     * Validate task ID against permitted identifiers in Info.plist
     */
    private fun validateTaskId(id: String): Boolean {
        if (id !in permittedTaskIds) {
            Logger.e(LogTags.SCHEDULER, """
                ❌ Task ID '$id' validation failed

                Permitted IDs: ${permittedTaskIds.joinToString()}

                To fix:
                1. Add '$id' to Info.plist > BGTaskSchedulerPermittedIdentifiers:
                   <key>BGTaskSchedulerPermittedIdentifiers</key>
                   <array>
                       <string>$id</string>
                   </array>

                2. Register task handler in AppDelegate/iOSApp.swift:
                   BGTaskScheduler.shared.register(forTaskWithIdentifier: "$id") { task in
                       // Handle task
                   }
            """.trimIndent())
            return false
        }
        return true
    }

    /**
     * Schedule a periodic task with automatic re-scheduling
     */
    private fun schedulePeriodicTask(
        id: String,
        trigger: TaskTrigger.Periodic,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Scheduling periodic task - ID: '$id', Interval: ${trigger.intervalMs}ms")

        // Handle ExistingPolicy
        if (!handleExistingPolicy(id, policy, isPeriodicMetadata = true)) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' already exists, KEEP policy - skipping")
            return ScheduleResult.ACCEPTED
        }

        // Save metadata for re-scheduling after execution
        val periodicMetadata = mapOf(
            "isPeriodic" to "true",
            "intervalMs" to "${trigger.intervalMs}",
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "requiresNetwork" to "${constraints.requiresNetwork}",
            "requiresCharging" to "${constraints.requiresCharging}",
            "isHeavyTask" to "${constraints.isHeavyTask}"
        )
        fileStorage.saveTaskMetadata(id, periodicMetadata, periodic = true)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.intervalMs / 1000.0)

        return submitTaskRequest(request, "periodic task '$id'")
    }

    /**
     * Schedule a one-time task
     */
    private fun scheduleOneTimeTask(
        id: String,
        trigger: TaskTrigger.OneTime,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Scheduling one-time task - ID: '$id', Delay: ${trigger.initialDelayMs}ms")

        // Handle ExistingPolicy
        if (!handleExistingPolicy(id, policy, isPeriodicMetadata = false)) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' already exists, KEEP policy - skipping")
            return ScheduleResult.ACCEPTED
        }

        val taskMetadata = mapOf(
            "workerClassName" to (workerClassName ?: ""),
            "inputJson" to (inputJson ?: "")
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.initialDelayMs / 1000.0)

        return submitTaskRequest(request, "one-time task '$id'")
    }

    /**
     * Handle ExistingPolicy - returns true if should proceed with scheduling, false if should skip
     */
    private fun handleExistingPolicy(id: String, policy: ExistingPolicy, isPeriodicMetadata: Boolean): Boolean {
        val existingMetadata = fileStorage.loadTaskMetadata(id, periodic = isPeriodicMetadata)

        if (existingMetadata != null) {
            Logger.d(LogTags.SCHEDULER, "Task '$id' metadata exists, policy: $policy")

            when (policy) {
                ExistingPolicy.KEEP -> {
                    // Check if task is still pending in BGTaskScheduler
                    // Note: BGTaskScheduler doesn't provide API to query pending requests,
                    // so we rely on metadata existence as proxy
                    return false
                }
                ExistingPolicy.REPLACE -> {
                    Logger.i(LogTags.SCHEDULER, "Replacing existing task '$id'")
                    cancel(id)
                    return true
                }
            }
        }
        return true
    }

    /**
     * Create appropriate background task request based on constraints
     * Note: iOS BGTaskScheduler does not have a direct QoS API. QoS is managed by iOS based on:
     * - Task type (BGAppRefreshTask vs BGProcessingTask)
     * - System conditions (battery, network, etc.)
     * - App priority and background refresh settings
     */
    private fun createBackgroundTaskRequest(id: String, constraints: Constraints): BGTaskRequest {
        // Log QoS level for developer awareness (iOS manages actual priority automatically)
        Logger.d(LogTags.SCHEDULER, "Task QoS level: ${constraints.qos} (iOS manages priority automatically)")

        return if (constraints.isHeavyTask) {
            Logger.d(LogTags.SCHEDULER, "Creating BGProcessingTaskRequest for heavy task")
            BGProcessingTaskRequest(identifier = id).apply {
                requiresExternalPower = constraints.requiresCharging
                requiresNetworkConnectivity = constraints.requiresNetwork
            }
        } else {
            Logger.d(LogTags.SCHEDULER, "Creating BGAppRefreshTaskRequest for light task")
            BGAppRefreshTaskRequest(identifier = id)
        }
    }

    /**
     * Submit task request to BGTaskScheduler with proper error handling
     */
    private fun submitTaskRequest(request: BGTaskRequest, taskDescription: String): ScheduleResult {
        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val success = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)

            if (success) {
                Logger.i(LogTags.SCHEDULER, "Successfully submitted $taskDescription")
                ScheduleResult.ACCEPTED
            } else {
                val error = errorPtr.value
                val errorMessage = error?.localizedDescription ?: "Unknown error"
                Logger.e(LogTags.SCHEDULER, "Failed to submit $taskDescription: $errorMessage")
                ScheduleResult.REJECTED_OS_POLICY
            }
        }
    }

    /**
     * Schedule exact notification using UNUserNotificationCenter
     */
    private fun scheduleExactNotification(
        id: String,
        trigger: TaskTrigger.Exact,
        title: String,
        message: String?
    ): ScheduleResult {
        Logger.i(LogTags.ALARM, "Scheduling exact notification - ID: '$id', Time: ${trigger.atEpochMillis}")

        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message ?: "Scheduled event")
            setSound(UNNotificationSound.defaultSound)
        }

        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val date = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        val dateComponents = NSCalendar.currentCalendar.components(
            (NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
             NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond),
            fromDate = date
        )

        val notifTrigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
            dateComponents,
            repeats = false
        )
        val request = UNNotificationRequest.requestWithIdentifier(id, content, notifTrigger)

        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) {
                Logger.e(LogTags.ALARM, "Error scheduling notification '$id': ${error.localizedDescription}")
            } else {
                Logger.i(LogTags.ALARM, "Successfully scheduled exact notification '$id'")
            }
        }
        return ScheduleResult.ACCEPTED
    }

    /**
     * Reject unsupported trigger type with logging
     */
    private fun rejectUnsupportedTrigger(triggerName: String): ScheduleResult {
        Logger.w(LogTags.SCHEDULER, "$triggerName triggers not supported on iOS (Android only)")
        return ScheduleResult.REJECTED_OS_POLICY
    }

    actual override fun beginWith(task: TaskRequest): TaskChain {
        return TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        return TaskChain(this, tasks)
    }

    actual override fun enqueueChain(chain: TaskChain) {
        val steps = chain.getSteps()
        if (steps.isEmpty()) {
            Logger.w(LogTags.CHAIN, "Attempted to enqueue empty chain, ignoring")
            return
        }

        val chainId = NSUUID.UUID().UUIDString()
        Logger.i(LogTags.CHAIN, "Enqueuing chain - ID: $chainId, Steps: ${steps.size}")

        // 1. Save the chain definition
        fileStorage.saveChainDefinition(chainId, steps)

        // 2. Add the chainId to the execution queue (atomic operation)
        kotlinx.coroutines.MainScope().launch {
            try {
                fileStorage.enqueueChain(chainId)
                Logger.d(LogTags.CHAIN, "Added chain $chainId to execution queue. Queue size: ${fileStorage.getQueueSize()}")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Failed to enqueue chain $chainId", e)
                return@launch
            }
        }

        // 3. Schedule the generic chain executor task
        val request = BGProcessingTaskRequest(identifier = CHAIN_EXECUTOR_IDENTIFIER).apply {
            earliestBeginDate = NSDate().dateByAddingTimeInterval(1.0)
            requiresNetworkConnectivity = true
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val success = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)

            if (success) {
                Logger.i(LogTags.CHAIN, "Successfully submitted chain executor task")
            } else {
                val error = errorPtr.value
                Logger.e(LogTags.CHAIN, "Failed to submit chain executor: ${error?.localizedDescription}")
            }
        }
    }

    actual override fun cancel(id: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling task/notification with ID '$id'")

        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
        UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(listOf(id))

        // Clean up metadata from file storage
        fileStorage.deleteTaskMetadata(id, periodic = false)
        fileStorage.deleteTaskMetadata(id, periodic = true)

        Logger.d(LogTags.SCHEDULER, "Cancelled task '$id' and cleaned up metadata")
    }

    actual override fun cancelAll() {
        Logger.w(LogTags.SCHEDULER, "Cancelling ALL tasks and notifications")

        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()

        // Cleanup file storage (garbage collection)
        fileStorage.cleanupStaleMetadata(olderThanDays = 0) // Clean all metadata immediately

        Logger.d(LogTags.SCHEDULER, "Cancelled all tasks and cleaned up metadata")
    }
}
