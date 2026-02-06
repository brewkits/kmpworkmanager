
package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.sample.background.domain.*
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.*
import platform.posix.getenv
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
 * - ExistingPolicy support (KEEP/REPLACE)
 * - Task ID validation against Info.plist
 * - Proper error handling with NSError
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeTaskScheduler(
    private val singleTaskExecutor: SingleTaskExecutor? = null,
    private val chainExecutor: ChainExecutor? = null
) : BackgroundTaskScheduler {

    /**
     * On iOS Simulator, BGTaskScheduler.submitTaskRequest always fails (error 1).
     * When running on simulator, single tasks and chains are executed directly
     * in-process so the demo app remains interactive.
     *
     * Detection relies on an env var set by AppDelegate before Koin init,
     * guarded by Swift's compile-time `#if targetEnvironment(simulator)`.
     * This is the only reliable simulator detection available on iOS.
     */
    private val isSimulator: Boolean =
        getenv("KMP_IS_SIMULATOR") != null

    private val userDefaults = NSUserDefaults.standardUserDefaults

    private companion object {
        const val CHAIN_DEFINITION_PREFIX = "kmp_chain_definition_"
        const val TASK_META_PREFIX = "kmp_task_meta_"
        const val PERIODIC_META_PREFIX = "kmp_periodic_meta_"
        const val CHAIN_QUEUE_KEY = "kmp_chain_queue"
        const val CHAIN_EXECUTOR_IDENTIFIER = "kmp_chain_executor_task"
        const val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0

        /**
         * Permitted task identifiers that must match Info.plist BGTaskSchedulerPermittedIdentifiers
         * IMPORTANT: Tasks with IDs not in this list will be silently rejected by iOS
         */
        val PERMITTED_TASK_IDS = setOf(
            CHAIN_EXECUTOR_IDENTIFIER,
            "one-time-upload",
            "heavy-task-1",
            "periodic-sync-task",
            "network-task",
            "exact-reminder",
            "demo-task",
            "content-uri-task",
            "demo-quick-sync",
            "demo-file-upload",
            "demo-database",
            "demo-hourly-sync",
            "demo-daily-cleanup",
            "demo-location-sync",
            "demo-network-required",
            "demo-unmetered",
            "demo-charging",
            "demo-battery-ok",
            "demo-storage-low",
            "demo-device-idle",
            "demo-retry",
            "demo-db-fail",
            "demo-heavy",
            "demo-batch-upload",
            "demo-image-proc",
            "demo-builtin-httprequest",
            "demo-builtin-httpsync",
            "demo-builtin-httpdownload",
            "demo-builtin-httpupload",
            "demo-builtin-filecompression"
        )
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
        if (id !in PERMITTED_TASK_IDS) {
            Logger.w(LogTags.SCHEDULER, """
                Task ID '$id' validation failed.
                Permitted IDs: ${PERMITTED_TASK_IDS.joinToString()}
                Please add '$id' to Info.plist > BGTaskSchedulerPermittedIdentifiers
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
        userDefaults.setObject(periodicMetadata, forKey = "$PERIODIC_META_PREFIX$id")

        if (isSimulator) {
            // On simulator, execute once immediately (no re-scheduling — simulator only)
            simulatorExecuteSingle(id, workerClassName, inputJson, 0L)
            return ScheduleResult.ACCEPTED
        }

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
        userDefaults.setObject(taskMetadata, forKey = "$TASK_META_PREFIX$id")

        if (isSimulator) {
            simulatorExecuteSingle(id, workerClassName, inputJson, trigger.initialDelayMs)
            return ScheduleResult.ACCEPTED
        }

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.initialDelayMs / 1000.0)

        return submitTaskRequest(request, "one-time task '$id'")
    }

    /**
     * Handle ExistingPolicy - returns true if should proceed with scheduling, false if should skip
     */
    private fun handleExistingPolicy(id: String, policy: ExistingPolicy, isPeriodicMetadata: Boolean): Boolean {
        val metadataKey = if (isPeriodicMetadata) "$PERIODIC_META_PREFIX$id" else "$TASK_META_PREFIX$id"
        val existingMetadata = userDefaults.dictionaryForKey(metadataKey)

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
     */
    private fun createBackgroundTaskRequest(id: String, constraints: Constraints): BGTaskRequest {
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
    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
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

    // ---------------------------------------------------------------
    // Simulator fallback — executes tasks in-process so the demo app
    // remains interactive without requiring Xcode BGTask simulation.
    // ---------------------------------------------------------------

    private fun simulatorExecuteSingle(id: String, workerClassName: String, inputJson: String?, delayMs: Long) {
        CoroutineScope(Dispatchers.Default).launch {
            Logger.i(LogTags.SCHEDULER, "[Simulator] Waiting ${delayMs}ms then executing '$id' directly")
            delay(delayMs)
            val success = singleTaskExecutor?.executeTask(workerClassName, inputJson) ?: false
            Logger.i(LogTags.SCHEDULER, "[Simulator] Task '$id' completed directly, success=$success")
        }
    }

    private fun simulatorExecuteChains() {
        CoroutineScope(Dispatchers.Default).launch {
            Logger.i(LogTags.CHAIN, "[Simulator] Executing queued chains directly")
            try {
                val count = chainExecutor?.executeChainsInBatch(maxChains = 3, totalTimeoutMs = 50_000) ?: 0
                Logger.i(LogTags.CHAIN, "[Simulator] Executed $count chain(s) directly")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "[Simulator] Chain execution failed: ${e.message}")
            }
        }
    }

    actual override fun beginWith(task: TaskRequest): TaskChain {
        return TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        return TaskChain(this, tasks)
    }

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    actual override fun enqueueChain(chain: TaskChain) {
        val steps = chain.getSteps()
        if (steps.isEmpty()) {
            Logger.w(LogTags.CHAIN, "Attempted to enqueue empty chain, ignoring")
            return
        }

        val chainId = NSUUID.UUID().UUIDString()
        Logger.i(LogTags.CHAIN, "Enqueuing chain - ID: $chainId, Steps: ${steps.size}")

        // 1. Serialize and save the chain definition
        val chainJson = Json.encodeToString(steps)
        userDefaults.setObject(chainJson, forKey = "$CHAIN_DEFINITION_PREFIX$chainId")

        // 2. Add the chainId to the execution queue
        val stringArray: List<String>? = userDefaults.arrayForKey(CHAIN_QUEUE_KEY)?.filterIsInstance<String>()
        val queue: MutableList<String> = stringArray?.toMutableList() ?: mutableListOf()
        queue.add(chainId)
        userDefaults.setObject(queue, forKey = CHAIN_QUEUE_KEY)

        Logger.d(LogTags.CHAIN, "Added chain $chainId to execution queue. Queue size: ${queue.size}")

        // 3. Schedule the generic chain executor task (or run directly on simulator)
        if (isSimulator) {
            simulatorExecuteChains()
            return
        }

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
        userDefaults.removeObjectForKey("$TASK_META_PREFIX$id")
        userDefaults.removeObjectForKey("$PERIODIC_META_PREFIX$id")

        Logger.d(LogTags.SCHEDULER, "Cancelled task '$id' and cleaned up metadata")
    }

    actual override fun cancelAll() {
        Logger.w(LogTags.SCHEDULER, "Cancelling ALL tasks and notifications")

        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()

        // Note: Metadata cleanup would require iterating all keys, which is expensive
        // Metadata will be cleaned up when individual tasks complete or are cancelled
        Logger.d(LogTags.SCHEDULER, "Cancelled all tasks (metadata remains until individual task completion)")
    }
}
