package io.kmp.taskmanager.sample.background.data

import android.os.Build
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import io.kmp.taskmanager.sample.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.sample.background.domain.Constraints
import io.kmp.taskmanager.sample.background.domain.ExistingPolicy
import io.kmp.taskmanager.sample.background.domain.ScheduleResult
import io.kmp.taskmanager.sample.background.domain.TaskTrigger
import io.kmp.taskmanager.sample.utils.Logger
import io.kmp.taskmanager.sample.utils.LogTags
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
        Logger.i(LogTags.SCHEDULER, "Enqueue request - ID: '$id', Trigger: ${trigger::class.simpleName}, Policy: $policy")

        return when (trigger) {
            is TaskTrigger.Periodic -> {
                schedulePeriodicWork(id, trigger, workerClassName, constraints, inputJson, policy)
            }

            is TaskTrigger.Exact -> {
                scheduleExactAlarm(id, trigger, workerClassName, inputJson)
            }

            is TaskTrigger.Windowed -> {
                Logger.w(LogTags.SCHEDULER, "Windowed triggers not yet implemented on Android")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling periodic task - ID: '$id', Interval: ${trigger.intervalMs}ms")

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

        Logger.i(LogTags.SCHEDULER, "Successfully enqueued periodic task '$id' to WorkManager")
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
        Logger.i(LogTags.ALARM, "Scheduling exact alarm - ID: '$id', Time: ${trigger.atEpochMillis}")

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
            Logger.i(LogTags.ALARM, "Successfully scheduled exact alarm '$id'")
        }

        // Check if the device is Android 12 (API 31) or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On newer devices, we must check if we have the permission
            if (alarmManager.canScheduleExactAlarms()) {
                setAlarm()
                return ScheduleResult.ACCEPTED
            } else {
                Logger.e(LogTags.ALARM, "Cannot schedule exact alarm '$id' - SCHEDULE_EXACT_ALARM permission missing (Android 12+)")
                return ScheduleResult.REJECTED_OS_POLICY
            }
        } else {
            // On older devices, the permission is granted by default at install time
            setAlarm()
            return ScheduleResult.ACCEPTED
        }
    }

    private fun scheduleOneTimeWork(
        id: String,
        trigger: TaskTrigger.OneTime,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Scheduling one-time task - ID: '$id', Delay: ${trigger.initialDelayMs}ms")

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
            Logger.d(LogTags.SCHEDULER, "Creating HEAVY one-time task")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            Logger.d(LogTags.SCHEDULER, "Creating REGULAR one-time task")
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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued one-time task '$id' to WorkManager")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling ContentUri task - ID: '$id', URI: ${trigger.uriString}")

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
            Logger.d(LogTags.SCHEDULER, "Creating HEAVY ContentUri task")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            Logger.d(LogTags.SCHEDULER, "Creating REGULAR ContentUri task")
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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued ContentUri task '$id'")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling StorageLow task - ID: '$id'")

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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued StorageLow task '$id'")
        Logger.w(LogTags.SCHEDULER, "Note: This allows running during low storage but doesn't actively trigger on low storage events")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling BatteryLow task - ID: '$id'")

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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued BatteryLow task '$id'")
        Logger.w(LogTags.SCHEDULER, "Note: This allows running during low battery but doesn't actively trigger on low battery events")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling BatteryOkay task - ID: '$id'")

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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued BatteryOkay task '$id'")
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
        Logger.i(LogTags.SCHEDULER, "Scheduling DeviceIdle task - ID: '$id'")

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
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued DeviceIdle task '$id'")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Cancels a task by its unique ID.
     * Note: This implementation currently only cancels WorkManager tasks.
     * A full implementation would need to also cancel PendingIntents for AlarmManager.
     */
    actual override fun cancel(id: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling task with ID '$id'")
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
            Logger.d(LogTags.ALARM, "Cancelled alarm with ID '$id'")
        }
    }

    /**
     * Cancels all scheduled tasks.
     */
    actual override fun cancelAll() {
        Logger.w(LogTags.SCHEDULER, "Cancelling ALL background tasks")
        workManager.cancelAllWork()
        Logger.d(LogTags.SCHEDULER, "Cancelled all WorkManager tasks (alarms require individual cancellation)")
    }

    actual override fun beginWith(task: io.kmp.taskmanager.sample.background.domain.TaskRequest): io.kmp.taskmanager.sample.background.domain.TaskChain {
        return io.kmp.taskmanager.sample.background.domain.TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<io.kmp.taskmanager.sample.background.domain.TaskRequest>): io.kmp.taskmanager.sample.background.domain.TaskChain {
        return io.kmp.taskmanager.sample.background.domain.TaskChain(this, tasks)
    }

    actual override fun enqueueChain(chain: io.kmp.taskmanager.sample.background.domain.TaskChain) {
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
        Logger.i(LogTags.CHAIN, "Successfully enqueued task chain with ${steps.size} steps")
    }

    private fun createWorkRequest(task: io.kmp.taskmanager.sample.background.domain.TaskRequest): OneTimeWorkRequest {
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
            Logger.d(LogTags.CHAIN, "Creating HEAVY task in chain: ${task.workerClassName}")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            Logger.d(LogTags.CHAIN, "Creating REGULAR task in chain: ${task.workerClassName}")
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