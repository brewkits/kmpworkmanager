package com.example.kmpworkmanagerv2.background.data

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.dateByAddingTimeInterval
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.UserNotifications.UNNotificationSound

/**
 * The `actual` implementation for the iOS platform.
 */
@OptIn(ExperimentalForeignApi::class)
actual class NativeTaskScheduler : BackgroundTaskScheduler {

    // Hằng số chênh lệch giây giữa Unix epoch (1970) và Apple epoch (2001)
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

        return when (trigger) {
            is TaskTrigger.Periodic -> scheduleBackgroundTask(id, trigger.intervalMs, constraints.isHeavyTask)
            is TaskTrigger.OneTime -> scheduleBackgroundTask(id, trigger.initialDelayMs, constraints.isHeavyTask)
            is TaskTrigger.Exact -> scheduleExactNotification(id, trigger, workerClassName, inputJson)
            else -> ScheduleResult.REJECTED_OS_POLICY
        }
    }

    private fun scheduleBackgroundTask(id: String, delayMs: Long, isHeavy: Boolean): ScheduleResult {
        val request = if (isHeavy) {
            println(" KMP_BG_TASK_iOS: Scheduling as a HEAVY task (BGProcessingTaskRequest).")
            BGProcessingTaskRequest(identifier = id).apply {
                // Tác vụ nặng thường yêu cầu sạc và mạng
                requiresExternalPower = true
                requiresNetworkConnectivity = true
            }
        } else {
            println(" KMP_BG_TASK_iOS: Scheduling as a REGULAR task (BGAppRefreshTaskRequest).")
            BGAppRefreshTaskRequest(identifier = id)
        }

        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(delayMs / 1000.0)

        return try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            println(" KMP_BG_TASK_iOS: Submitted background task '$id' successfully.")
            ScheduleResult.ACCEPTED
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: Failed to submit background task '$id'. Error: ${e.message}")
            ScheduleResult.REJECTED_OS_POLICY
        }
    }

    private fun scheduleExactNotification(
        id: String,
        trigger: TaskTrigger.Exact,
        title: String,
        message: String?
    ): ScheduleResult {
        val content = platform.UserNotifications.UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message ?: "Scheduled event")
            setSound(UNNotificationSound.defaultSound)
        }

        println("KMP_BG_TASK_iOS: Scheduling notification with title: '$title', body: '${message ?: "Scheduled event"}'")

        // FIX 2: Chuyển đổi từ Unix epoch (1970) sang Apple's reference epoch (2001)
        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val date = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        val dateComponents = NSCalendar.currentCalendar.components(
            (NSCalendarUnitYear or
                    NSCalendarUnitMonth or
                    NSCalendarUnitDay or
                    NSCalendarUnitHour or
                    NSCalendarUnitMinute or
                    NSCalendarUnitSecond),
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
    }

    actual override fun cancelAll() {
        println(" KMP_BG_TASK_iOS: Cancelling ALL tasks and notifications.")
        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
    }
}
