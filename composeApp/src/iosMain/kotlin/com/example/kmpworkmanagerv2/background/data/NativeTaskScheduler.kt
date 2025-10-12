
package com.example.kmpworkmanagerv2.background.data

import com.example.kmpworkmanagerv2.background.domain.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.* // For NSUserDefaults, NSDate, etc.
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The `actual` implementation for the iOS platform, using `BGTaskScheduler` for background
 * tasks and `UNUserNotificationCenter` for exact time scheduling (via notifications).
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeTaskScheduler : BackgroundTaskScheduler {

    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val CHAIN_DEFINITION_PREFIX = "kmp_chain_definition_"
    private val TASK_META_PREFIX = "kmp_task_meta_"
    private val PERIODIC_META_PREFIX = "kmp_periodic_meta_"
    private val CHAIN_QUEUE_KEY = "kmp_chain_queue"
    private val CHAIN_EXECUTOR_IDENTIFIER = "kmp_chain_executor_task"

    // Constant for the time difference in seconds between Unix epoch (1970) and Apple epoch (2001)
    private val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0

    actual override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK_iOS: Received enqueue request for id='$id', trigger=${trigger::class.simpleName}")

        // TODO: Handle policy

        return when (trigger) {
            is TaskTrigger.Periodic -> {
                println(" KMP_BG_TASK_iOS: Saving metadata for periodic task '$id'.")
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

                val request = if (constraints.isHeavyTask) {
                    BGProcessingTaskRequest(identifier = id).apply {
                        requiresExternalPower = constraints.requiresCharging
                        requiresNetworkConnectivity = constraints.requiresNetwork
                    }
                } else {
                    BGAppRefreshTaskRequest(identifier = id)
                }
                request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.intervalMs / 1000.0)

                try {
                    BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
                    println(" KMP_BG_TASK_iOS: Submitted initial periodic task '$id' successfully.")
                    ScheduleResult.ACCEPTED
                } catch (e: Exception) {
                    println(" KMP_BG_TASK_iOS: Failed to submit initial periodic task '$id'. Error: ${e.message}")
                    ScheduleResult.REJECTED_OS_POLICY
                }
            }
            is TaskTrigger.OneTime -> {
                val taskMetadata = mapOf(
                    "workerClassName" to (workerClassName ?: ""),
                    "inputJson" to (inputJson ?: "")
                )
                userDefaults.setObject(taskMetadata, forKey = "$TASK_META_PREFIX$id")

                val request = if (constraints.isHeavyTask) {
                    BGProcessingTaskRequest(identifier = id).apply {
                        requiresExternalPower = constraints.requiresCharging
                        requiresNetworkConnectivity = constraints.requiresNetwork
                    }
                } else {
                    BGAppRefreshTaskRequest(identifier = id)
                }

                request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.initialDelayMs / 1000.0)

                try {
                    BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
                    println(" KMP_BG_TASK_iOS: Submitted background task '$id' successfully.")
                    ScheduleResult.ACCEPTED
                } catch (e: Exception) {
                    println(" KMP_BG_TASK_iOS: Failed to submit background task '$id'. Error: ${e.message}")
                    ScheduleResult.REJECTED_OS_POLICY
                }
            }
            is TaskTrigger.Exact -> scheduleExactNotification(id, trigger, workerClassName, inputJson)
            else -> ScheduleResult.REJECTED_OS_POLICY
        }
    }

    actual override fun beginWith(task: TaskRequest): TaskChain {
        return TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        return TaskChain(this, tasks)
    }

    actual override fun enqueueChain(chain: TaskChain) {
        val steps = chain.getSteps()
        if (steps.isEmpty()) return

        val chainId = NSUUID.UUID().UUIDString()
        println("KMP_BG_TASK_iOS: Enqueuing chain with ID: $chainId")

        // 1. Serialize and save the chain definition.
        val chainJson = Json.encodeToString(steps)
        userDefaults.setObject(chainJson, forKey = "$CHAIN_DEFINITION_PREFIX$chainId")

        // 2. Add the chainId to the execution queue.
        val stringArray: List<String>? = userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) as? List<String>
        val queue: MutableList<String> = if (stringArray != null) {
            stringArray.toMutableList()
        } else {
            mutableListOf<String>()
        }
        queue.add(chainId)
        userDefaults.setObject(queue, forKey = CHAIN_QUEUE_KEY)
        println("KMP_BG_TASK_iOS: Added $chainId to execution queue. Queue size: ${queue.size}")

        // 3. Schedule the generic chain executor task.
        // The native handler will process one ID from the queue at a time.
        val request = BGProcessingTaskRequest(identifier = CHAIN_EXECUTOR_IDENTIFIER)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(1.0) // Start soon
        request.requiresNetworkConnectivity = true // Assume chains might need network.

        try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            println(" KMP_BG_TASK_iOS: Submitted generic chain executor task successfully.")
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: Failed to submit generic chain executor task. Error: ${e.message}")
        }
    }

    private fun scheduleExactNotification(
        id: String,
        trigger: TaskTrigger.Exact,
        title: String,
        message: String?
    ): ScheduleResult {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message ?: "Scheduled event")
            setSound(UNNotificationSound.defaultSound)
        }

        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val date = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        val dateComponents = NSCalendar.currentCalendar.components(
            (NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond),
            fromDate = date
        )

        val notifTrigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(dateComponents, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(id, content, notifTrigger)

        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) {
                println(" KMP_BG_TASK_iOS: Error scheduling notification: ${error.localizedDescription}")
            } else {
                println(" KMP_BG_TASK_iOS: Scheduled exact notification '$id' successfully.")
            }
        }
        return ScheduleResult.ACCEPTED
    }

    actual override fun cancel(id: String) {
        println(" KMP_BG_TASK_iOS: Cancelling task/notification with ID '$id'.")
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
        UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(listOf(id))
        userDefaults.removeObjectForKey("$TASK_META_PREFIX$id")
        userDefaults.removeObjectForKey("$PERIODIC_META_PREFIX$id") // Also remove periodic metadata
    }

    actual override fun cancelAll() {
        println(" KMP_BG_TASK_iOS: Cancelling ALL tasks and notifications.")
        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
    }
}
