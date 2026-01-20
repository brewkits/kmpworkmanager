
package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
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

        @Suppress("DEPRECATION")  // Keep backward compatibility for deprecated triggers until v3.0.0
        return when (trigger) {
            is TaskTrigger.Periodic -> schedulePeriodicTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.OneTime -> scheduleOneTimeTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Exact -> scheduleExactAlarm(id, trigger, workerClassName, constraints, inputJson)
            is TaskTrigger.Windowed -> scheduleWindowedTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.ContentUri -> rejectUnsupportedTrigger("ContentUri")
            // v2.1.1+: These triggers are deprecated (use SystemConstraint instead), but kept for backward compatibility
            // Will be removed in v3.0.0
            TaskTrigger.StorageLow -> rejectUnsupportedTrigger("StorageLow (deprecated - use Constraints.systemConstraints)")
            TaskTrigger.BatteryLow -> rejectUnsupportedTrigger("BatteryLow (deprecated - use Constraints.systemConstraints)")
            TaskTrigger.BatteryOkay -> rejectUnsupportedTrigger("BatteryOkay (deprecated - use Constraints.systemConstraints)")
            TaskTrigger.DeviceIdle -> rejectUnsupportedTrigger("DeviceIdle (deprecated - use Constraints.systemConstraints)")
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
     * v2.0.1+: Now suspend to support improved ExistingPolicy.KEEP logic
     */
    private suspend fun schedulePeriodicTask(
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
     * v2.0.1+: Now suspend to support improved ExistingPolicy.KEEP logic
     */
    private suspend fun scheduleOneTimeTask(
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
     * Schedule a windowed task (execute within a time window).
     *
     * **iOS Limitation**: iOS's BGTaskScheduler only supports `earliestBeginDate`.
     * There is no "latest" deadline - the system decides when to run the task
     * opportunistically based on device conditions.
     *
     * **Implementation**:
     * - `earliest` → Maps to `earliestBeginDate`
     * - `latest` → Logged as a warning, but not enforced by iOS
     *
     * **Best Practice**: Design your app logic to not depend on the task
     * running before the `latest` time. Use exact alarms if strict timing is required.
     *
     * v2.0.1+: Now suspend to support improved ExistingPolicy.KEEP logic
     *
     * @param id Unique task identifier
     * @param trigger Windowed trigger with earliest and latest times
     * @param workerClassName Worker class name
     * @param constraints Execution constraints
     * @param inputJson Worker input data
     * @param policy Policy for handling existing tasks
     */
    private suspend fun scheduleWindowedTask(
        id: String,
        trigger: TaskTrigger.Windowed,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        val earliestDate = NSDate.dateWithTimeIntervalSince1970(trigger.earliest / 1000.0)
        val latestDate = NSDate.dateWithTimeIntervalSince1970(trigger.latest / 1000.0)

        Logger.i(
            LogTags.SCHEDULER,
            "Scheduling windowed task - ID: '$id', Window: ${earliestDate} to ${latestDate}"
        )
        Logger.w(
            LogTags.SCHEDULER,
            "⚠️ iOS BGTaskScheduler does not support 'latest' deadline. Task may run after the specified window."
        )

        // Handle ExistingPolicy
        if (!handleExistingPolicy(id, policy, isPeriodicMetadata = false)) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' already exists, KEEP policy - skipping")
            return ScheduleResult.ACCEPTED
        }

        val taskMetadata = mapOf(
            "workerClassName" to (workerClassName ?: ""),
            "inputJson" to (inputJson ?: ""),
            "windowEarliest" to trigger.earliest.toString(),
            "windowLatest" to trigger.latest.toString()
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = earliestDate

        return submitTaskRequest(request, "windowed task '$id'")
    }

    /**
     * Handle ExistingPolicy - returns true if should proceed with scheduling, false if should skip
     * v2.0.1+: Enhanced KEEP logic to query actual pending tasks, preventing stale metadata issues
     */
    private suspend fun handleExistingPolicy(id: String, policy: ExistingPolicy, isPeriodicMetadata: Boolean): Boolean {
        val existingMetadata = fileStorage.loadTaskMetadata(id, periodic = isPeriodicMetadata)

        if (existingMetadata != null) {
            Logger.d(LogTags.SCHEDULER, "Task '$id' metadata exists, policy: $policy")

            when (policy) {
                ExistingPolicy.KEEP -> {
                    // v2.0.1+: Query BGTaskScheduler to verify task is actually pending
                    // This prevents issues with stale metadata after crashes
                    val isPending = isTaskPending(id)

                    if (isPending) {
                        Logger.i(LogTags.SCHEDULER, "Task '$id' is pending in BGTaskScheduler, keeping existing task")
                        return false
                    } else {
                        Logger.w(LogTags.SCHEDULER, "Task '$id' metadata exists but not pending (stale). Cleaning up and rescheduling.")
                        fileStorage.deleteTaskMetadata(id, periodic = isPeriodicMetadata)
                        return true
                    }
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
     * Check if a task is actually pending in BGTaskScheduler.
     * v2.0.1+: Added to support reliable ExistingPolicy.KEEP logic
     * v2.1.1+: Uses suspendCancellableCoroutine to prevent crash if cancelled before callback executes
     */
    private suspend fun isTaskPending(taskId: String): Boolean = suspendCancellableCoroutine { continuation ->
        BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { requests ->
            // v2.1.1+: Check if continuation is still active before resuming
            if (continuation.isActive) {
                val taskList = requests?.filterIsInstance<BGTaskRequest>() ?: emptyList()
                val isPending = taskList.any { it.identifier == taskId }
                continuation.resume(isPending)
            } else {
                Logger.d(LogTags.SCHEDULER, "isTaskPending cancelled for $taskId - callback ignored")
            }
        }

        // v2.1.1+: Cleanup on cancellation
        continuation.invokeOnCancellation {
            Logger.d(LogTags.SCHEDULER, "isTaskPending cancelled for $taskId")
        }
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
     * Schedule exact alarm with iOS-specific behavior handling.
     *
     * **v2.1.1+**: Implements ExactAlarmIOSBehavior for transparent exact alarm handling.
     *
     * **Background**: iOS does NOT support background code execution at exact times.
     * This method provides three explicit behaviors based on constraints.exactAlarmIOSBehavior:
     * 1. SHOW_NOTIFICATION (default): Display UNNotification at exact time
     * 2. ATTEMPT_BACKGROUND_RUN: Schedule BGAppRefreshTask (not guaranteed to run at exact time)
     * 3. THROW_ERROR: Throw exception to force developer awareness
     *
     * @param id Task identifier
     * @param trigger Exact trigger with timestamp
     * @param workerClassName Worker class (used only for ATTEMPT_BACKGROUND_RUN, ignored for SHOW_NOTIFICATION)
     * @param constraints Execution constraints (contains exactAlarmIOSBehavior)
     * @param inputJson Worker input data
     * @return ScheduleResult indicating success/failure
     * @throws UnsupportedOperationException if exactAlarmIOSBehavior is THROW_ERROR
     */
    private fun scheduleExactAlarm(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?
    ): ScheduleResult {
        val behavior = constraints.exactAlarmIOSBehavior

        Logger.i(
            LogTags.ALARM,
            "Scheduling exact alarm - ID: '$id', Time: ${trigger.atEpochMillis}, Behavior: $behavior"
        )

        return when (behavior) {
            ExactAlarmIOSBehavior.SHOW_NOTIFICATION -> {
                scheduleExactNotification(id, trigger, workerClassName, inputJson)
            }

            ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN -> {
                Logger.w(
                    LogTags.ALARM,
                    "⚠️ ATTEMPT_BACKGROUND_RUN: iOS will TRY to run around specified time, but timing is NOT guaranteed"
                )
                scheduleExactBackgroundTask(id, trigger, workerClassName, constraints, inputJson)
            }

            ExactAlarmIOSBehavior.THROW_ERROR -> {
                val errorMessage = """
                    ❌ iOS does not support exact alarms for background code execution.

                    TaskTrigger.Exact on iOS can only:
                    1. Show notification at exact time (SHOW_NOTIFICATION)
                    2. Attempt opportunistic background run (ATTEMPT_BACKGROUND_RUN - not guaranteed)

                    To fix this error, choose one of:

                    Option 1: Show notification (user-facing events)
                    Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION)

                    Option 2: Best-effort background run (non-critical sync)
                    Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN)

                    Option 3: Platform-specific implementation
                    if (Platform.isIOS) {
                        // Use notification or rethink approach
                    } else {
                        // Use TaskTrigger.Exact on Android
                    }

                    See: ExactAlarmIOSBehavior documentation
                """.trimIndent()

                Logger.e(LogTags.ALARM, errorMessage)
                throw UnsupportedOperationException(errorMessage)
            }
        }
    }

    /**
     * Schedule exact notification using UNUserNotificationCenter.
     * This is the default and recommended approach for exact alarms on iOS.
     *
     * v2.1.1+: Made private, called from scheduleExactAlarm
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
     * Schedule background task to run around the exact time (best effort).
     *
     * **IMPORTANT**: iOS decides when to actually run the task. May be delayed by
     * minutes to hours, or may not run at all.
     *
     * v2.1.1+: Added for ATTEMPT_BACKGROUND_RUN behavior
     */
    private fun scheduleExactBackgroundTask(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?
    ): ScheduleResult {
        Logger.i(
            LogTags.ALARM,
            "Scheduling best-effort background task - ID: '$id', Target time: ${trigger.atEpochMillis}"
        )

        // Save metadata for task execution
        val taskMetadata = mapOf(
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "targetTime" to trigger.atEpochMillis.toString()
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        // Schedule BGAppRefreshTask with earliestBeginDate = exact time
        val request = createBackgroundTaskRequest(id, constraints)

        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val targetDate = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        request.earliestBeginDate = targetDate

        Logger.w(
            LogTags.ALARM,
            """
            ⚠️ Best-effort scheduling - iOS will ATTEMPT to run around ${targetDate}
            - Timing is NOT guaranteed (may be delayed significantly)
            - May not run if device is in Low Power Mode
            - May not run if app exceeded background budget
            """.trimIndent()
        )

        return submitTaskRequest(request, "best-effort background task '$id'")
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
        // v2.0.1+: Use background scope to avoid blocking Main thread with file I/O
        backgroundScope.launch {
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
