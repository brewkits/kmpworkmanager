package com.example.kmpworkmanagerv2.background.data

import android.os.Build
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

import androidx.work.OneTimeWorkRequestBuilder

/**
 * The `actual` implementation for the Android platform.
 * This class acts as a bridge between the shared KMP domain logic and the
 * native Android scheduling APIs (WorkManager and AlarmManager).
 */
actual class NativeTaskScheduler(private val context: Context) : BackgroundTaskScheduler {

    private val workManager = WorkManager.getInstance(context)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val TAG_KMP_TASK = "KMP_TASK"
    }

    /**
     * Enqueues a task based on its trigger type.
     * - `Periodic` triggers use WorkManager for efficient, deferrable background work.
     * - `Exact` triggers use AlarmManager for time-critical, user-facing events like reminders.
     */
    actual override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Received enqueue request for id='$id', trigger=${trigger::class.simpleName}")

        return when (trigger) {
            is TaskTrigger.Periodic -> {
                schedulePeriodicWork(id, trigger, workerClassName, constraints, inputJson, policy)
            }

            is TaskTrigger.Exact -> {
                scheduleExactAlarm(id, trigger, workerClassName, inputJson)
            }

            is TaskTrigger.Windowed -> {
                // Future implementation for windowed tasks using WorkManager's setInitialDelay
                println(" KMP_BG_TASK: Windowed tasks not yet implemented.")
                ScheduleResult.REJECTED_OS_POLICY
            }

            is TaskTrigger.OneTime -> {
                scheduleOneTimeWork(id, trigger, workerClassName, constraints, inputJson, policy)
            }

            is TaskTrigger.ContentUri -> {
                scheduleContentUriWork(id, trigger, workerClassName, constraints, inputJson, policy)
            }

            TaskTrigger.StorageLow -> {
                scheduleStorageLowWork(id, workerClassName, constraints, inputJson, policy)
            }

            TaskTrigger.BatteryLow -> {
                scheduleBatteryLowWork(id, workerClassName, constraints, inputJson, policy)
            }

            TaskTrigger.BatteryOkay -> {
                scheduleBatteryOkayWork(id, workerClassName, constraints, inputJson, policy)
            }

            TaskTrigger.DeviceIdle -> {
                scheduleDeviceIdleWork(id, workerClassName, constraints, inputJson, policy)
            }
        }
    }

    /**
     * Schedules a periodic task using Android's WorkManager.
     */
    private fun schedulePeriodicWork(
        id: String,
        trigger: TaskTrigger.Periodic,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Scheduling with WorkManager. Interval: ${trigger.intervalMs.milliseconds}")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingPeriodicWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingPeriodicWorkPolicy.REPLACE
        }

        // Pass the target worker's class name and any input data to the KmpWorker
        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        // Map our KMP Constraints to WorkManager's Constraints
        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<KmpWorker>(
            trigger.intervalMs,
            TimeUnit.MILLISECONDS
        )
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-periodic")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniquePeriodicWork(id, workManagerPolicy, workRequest)

        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Enqueued periodic work with ID '$id' to WorkManager.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules an exact alarm using Android's AlarmManager.
     * This is for tasks that must fire at a specific time.
     */
    private fun scheduleExactAlarm(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String, // In this case, it's just for logging/data
        inputJson: String?
    ): ScheduleResult {
        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Scheduling with AlarmManager. Trigger time: ${trigger.atEpochMillis}")

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("title", workerClassName) // Use workerClassName as title
            putExtra("message", inputJson)
            putExtra("notificationId", id.hashCode()) // Use hash of ID for notification ID
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(), // Use the unique ID hash as the request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // A Helper function to actually set the alarm
        fun setAlarm() {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger.atEpochMillis,
                pendingIntent
            )
            println(" KMP_BG_TASK: Set exact alarm with ID '$id'.")
        }

        // --- START: FIX FOR API LEVEL ---
        // Check if the device is Android 12 (API 31) or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On newer devices, we must check if we have the permission
            if (alarmManager.canScheduleExactAlarms()) {
                setAlarm()
                return ScheduleResult.ACCEPTED
            } else {
                // --- ADDED LOG ---
                println(" KMP_BG_TASK: Cannot schedule exact alarm. Permission 'SCHEDULE_EXACT_ALARM' is missing on Android 12+.")
                return ScheduleResult.REJECTED_OS_POLICY
            }
        } else {
            // On older devices, the permission is granted by default at install time
            setAlarm()
            return ScheduleResult.ACCEPTED
        }
        // --- END: FIX FOR API LEVEL ---
    }

    private fun scheduleOneTimeWork(
        id: String,
        trigger: TaskTrigger.OneTime,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling One-Time task with WorkManager. Delay: ${trigger.initialDelayMs.milliseconds}")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            println(" KMP_BG_TASK: Scheduling as a HEAVY one-time task.")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            println(" KMP_BG_TASK: Scheduling as a REGULAR one-time task.")
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setInitialDelay(trigger.initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-one-time")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued One-Time work with ID '$id' to WorkManager.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules work to run when a content URI changes (Android only).
     * Uses WorkManager's ContentUriTriggers.
     */
    private fun scheduleContentUriWork(
        id: String,
        trigger: TaskTrigger.ContentUri,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling ContentUri work for URI: ${trigger.uriString}")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        // Build WorkManager constraints with ContentUriTrigger
        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .addContentUriTrigger(
                android.net.Uri.parse(trigger.uriString),
                trigger.triggerForDescendants
            )
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            println(" KMP_BG_TASK: Scheduling as a HEAVY ContentUri task.")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            println(" KMP_BG_TASK: Scheduling as a REGULAR ContentUri task.")
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-content-uri")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued ContentUri work with ID '$id'.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules work to run when storage is low (Android only).
     * Uses WorkManager's setRequiresStorageNotLow constraint (inverted logic).
     */
    private fun scheduleStorageLowWork(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling StorageLow work")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        // Note: WorkManager has setRequiresStorageNotLow, but we want to trigger ON low storage
        // For true "on storage low" trigger, we'd need a BroadcastReceiver for ACTION_DEVICE_STORAGE_LOW
        // Here we use WorkManager with requiresStorageNotLow = false (default)
        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .setRequiresStorageNotLow(false) // Allow running when storage is low
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-storage-low")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued StorageLow work with ID '$id'.")
        println(" KMP_BG_TASK: Note: This allows running during low storage but doesn't actively trigger on low storage events.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules work to run when battery is low (Android only).
     * Uses WorkManager's setRequiresBatteryNotLow constraint (inverted logic).
     */
    private fun scheduleBatteryLowWork(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling BatteryLow work")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        // Note: WorkManager has setRequiresBatteryNotLow, but we want to trigger ON low battery
        // For true "on battery low" trigger, we'd need a BroadcastReceiver for ACTION_BATTERY_LOW
        // Here we use WorkManager with requiresBatteryNotLow = false (default)
        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .setRequiresBatteryNotLow(false) // Allow running when battery is low
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-battery-low")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued BatteryLow work with ID '$id'.")
        println(" KMP_BG_TASK: Note: This allows running during low battery but doesn't actively trigger on low battery events.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules work to run when battery is okay/not low (Android only).
     * Uses WorkManager's setRequiresBatteryNotLow constraint.
     */
    private fun scheduleBatteryOkayWork(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling BatteryOkay work")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .setRequiresBatteryNotLow(true) // Only run when battery is NOT low
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-battery-okay")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued BatteryOkay work with ID '$id'.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Schedules work to run when device is idle (Android only).
     * Uses WorkManager's setRequiresDeviceIdle constraint.
     */
    private fun scheduleDeviceIdleWork(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println(" KMP_BG_TASK: Scheduling DeviceIdle work")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        val wmConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .setRequiresDeviceIdle(true) // Only run when device is idle
            .build()

        val workRequest = if (constraints.isHeavyTask) {
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            OneTimeWorkRequestBuilder<KmpWorker>()
        }
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-device-idle")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")
            .build()

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        println(" KMP_BG_TASK: Enqueued DeviceIdle work with ID '$id'.")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Cancels a task by its unique ID.
     * Note: This implementation currently only cancels WorkManager tasks.
     * A full implementation would need to also cancel PendingIntents for AlarmManager.
     */
    actual override fun cancel(id: String) {
        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Cancelling work with ID '$id'.")
        workManager.cancelUniqueWork(id)

        // To cancel an alarm, you need to recreate the exact same PendingIntent
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            println(" KMP_BG_TASK: Cancelled alarm with ID '$id'.")
        }
    }

    /**
     * Cancels all scheduled tasks.
     */
    actual override fun cancelAll() {
        // --- ADDED LOG ---
        println(" KMP_BG_TASK: Cancelling ALL background work.")
        workManager.cancelAllWork()
        // Note: Cancelling all alarms is complex and requires tracking all PendingIntents.
        // This is usually not needed. Cancelling WorkManager is often sufficient.
    }

    actual override fun beginWith(task: com.example.kmpworkmanagerv2.background.domain.TaskRequest): com.example.kmpworkmanagerv2.background.domain.TaskChain {
        return com.example.kmpworkmanagerv2.background.domain.TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<com.example.kmpworkmanagerv2.background.domain.TaskRequest>): com.example.kmpworkmanagerv2.background.domain.TaskChain {
        return com.example.kmpworkmanagerv2.background.domain.TaskChain(this, tasks)
    }

    actual override fun enqueueChain(chain: com.example.kmpworkmanagerv2.background.domain.TaskChain) {
        val steps = chain.getSteps()
        if (steps.isEmpty()) return

        // Create a list of WorkRequests for the first step
        val firstStepWorkRequests = steps.first().map { taskRequest ->
            createWorkRequest(taskRequest)
        }

        // Begin the chain
        var workContinuation = workManager.beginWith(firstStepWorkRequests)

        // Chain the subsequent steps
        steps.drop(1).forEach { parallelTasks ->
            val nextStepWorkRequests = parallelTasks.map { taskRequest ->
                createWorkRequest(taskRequest)
            }
            workContinuation = workContinuation.then(nextStepWorkRequests)
        }

        // Enqueue the entire chain
        workContinuation.enqueue()
        println(" KMP_BG_TASK: Enqueued task chain.")
    }

    private fun createWorkRequest(task: com.example.kmpworkmanagerv2.background.domain.TaskRequest): OneTimeWorkRequest {
        val workData = Data.Builder()
            .putString("workerClassName", task.workerClassName)
            .apply { task.inputJson?.let { putString("inputJson", it) } }
            .build()

        val constraints = task.constraints

        val wmConstraints = if (constraints != null) {
            val networkType = when {
                constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
                constraints.requiresNetwork -> NetworkType.CONNECTED
                else -> NetworkType.NOT_REQUIRED
            }
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresCharging(constraints.requiresCharging)
                .build()
        } else {
            androidx.work.Constraints.NONE
        }

        val workRequestBuilder = if (constraints?.isHeavyTask == true) {
            println(" KMP_BG_TASK: Scheduling as a HEAVY one-time task in a chain.")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            println(" KMP_BG_TASK: Scheduling as a REGULAR one-time task in a chain.")
            OneTimeWorkRequestBuilder<KmpWorker>()
        }

        return workRequestBuilder
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .addTag(TAG_KMP_TASK)
            .addTag("type-chain-member")
            .addTag("worker-${task.workerClassName}")
            .build()
    }
}