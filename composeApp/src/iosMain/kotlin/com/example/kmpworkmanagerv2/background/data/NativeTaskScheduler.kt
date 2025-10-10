package com.example.kmpworkmanagerv2.background.data

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.*
import platform.UserNotifications.*
import platform.BackgroundTasks.BGProcessingTaskRequest

/**
 * The `actual` implementation for the iOS platform, using `BGTaskScheduler` for background
 * tasks and `UNUserNotificationCenter` for exact time scheduling (via notifications).
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeTaskScheduler : BackgroundTaskScheduler {

    // Constant for the time difference in seconds between Unix epoch (1970) and Apple epoch (2001)
    private val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0

    /**
     * Enqueues a new background task based on the specified trigger type.
     */
    actual override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK_iOS: Received enqueue request for id='$id', trigger=${trigger::class.simpleName}")

        return when (trigger) {
            // Periodic and OneTime tasks are mapped to iOS BackgroundTasks (AppRefresh or Processing)
            is TaskTrigger.Periodic -> scheduleBackgroundTask(id, trigger.intervalMs, constraints)
            is TaskTrigger.OneTime -> scheduleBackgroundTask(id, trigger.initialDelayMs, constraints)
            // Exact tasks are mapped to a scheduled Local Notification (UNCalendarNotificationTrigger)
            is TaskTrigger.Exact -> scheduleExactNotification(id, trigger, workerClassName, inputJson)
            // Handle other potential triggers or unsupported types
            else -> ScheduleResult.REJECTED_OS_POLICY
        }
    }

    /**
     * Schedules a task using iOS's BGTaskScheduler (Background App Refresh or Processing).
     */
    private fun scheduleBackgroundTask(id: String, delayMs: Long, constraints: Constraints): ScheduleResult {
        val request = if (constraints.isHeavyTask) {
            println(" KMP_BG_TASK_iOS: Scheduling as a HEAVY task (BGProcessingTaskRequest).")
            // BGProcessingTaskRequest is for long-running, resource-intensive tasks
            BGProcessingTaskRequest(identifier = id).apply {
                requiresExternalPower = constraints.requiresCharging
                requiresNetworkConnectivity = constraints.requiresNetwork
            }
        } else {
            println(" KMP_BG_TASK_iOS: Scheduling as a REGULAR task (BGAppRefreshTaskRequest).")
            // BGAppRefreshTaskRequest is for quick, lightweight content updates
            BGAppRefreshTaskRequest(identifier = id)
        }

        // Set the earliest time the system should begin the task
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(delayMs / 1000.0)

        return try {
            // Submit the request to the system scheduler
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            println(" KMP_BG_TASK_iOS: Submitted background task '$id' successfully.")
            ScheduleResult.ACCEPTED
        } catch (e: Exception) {
            // Submission can fail if task ID is not registered in Info.plist or other OS policies
            println(" KMP_BG_TASK_iOS: Failed to submit background task '$id'. Error: ${e.message}")
            ScheduleResult.REJECTED_OS_POLICY
        }
    }

    /**
     * Schedules an exact time task using a `UNCalendarNotificationTrigger`.
     * This relies on the system delivering the local notification at the exact time.
     * The notification handler will then execute the worker logic.
     */
    private fun scheduleExactNotification(
        id: String,
        trigger: TaskTrigger.Exact,
        // The worker class name is passed as title/message to be used by the notification handler
        title: String, // Worker Class Name
        message: String? // Input JSON
    ): ScheduleResult {
        // Create the notification content
        val content = platform.UserNotifications.UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message ?: "Scheduled event")
            setSound(UNNotificationSound.defaultSound)
        }

        println("KMP_BG_TASK_iOS: Scheduling notification with title: '$title', body: '${message ?: "Scheduled event"}'")

        // FIX 2: Convert Unix epoch (1970) timestamp to Apple's reference epoch (2001) timestamp
        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val date = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        // Extract date components (Year, Month, Day, Hour, Minute, Second) from the target NSDate
        val dateComponents = NSCalendar.currentCalendar.components(
            (NSCalendarUnitYear or
                    NSCalendarUnitMonth or
                    NSCalendarUnitDay or
                    NSCalendarUnitHour or
                    NSCalendarUnitMinute or
                    NSCalendarUnitSecond),
            fromDate = date
        )

        // Create a trigger that fires when the system time matches the components
        val notifTrigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(dateComponents, repeats = false)
        // Create the request with a unique ID, content, and the calendar trigger
        val request = UNNotificationRequest.requestWithIdentifier(id, content, notifTrigger)

        // Add the notification request to the system
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
            if (error != null) {
                println(" KMP_BG_TASK_iOS: Error scheduling notification: ${error.localizedDescription}")
            } else {
                println(" KMP_BG_TASK_iOS: Scheduled exact notification '$id' successfully.")
            }
        }
        return ScheduleResult.ACCEPTED
    }

    /**
     * Cancels a specific pending background task and pending exact notification.
     */
    actual override fun cancel(id: String) {
        println(" KMP_BG_TASK_iOS: Cancelling task/notification with ID '$id'.")
        // Cancel BGTaskScheduler request
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
        // Cancel UNNotification request
        UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    /**
     * Cancels all pending background tasks and exact notifications.
     */
    actual override fun cancelAll() {
        println(" KMP_BG_TASK_iOS: Cancelling ALL tasks and notifications.")
        // Cancel all BGTaskScheduler requests
        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        // Cancel all UNNotification requests
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
    }
}