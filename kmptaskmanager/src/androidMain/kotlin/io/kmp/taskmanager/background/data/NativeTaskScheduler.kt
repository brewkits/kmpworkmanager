package io.kmp.taskmanager.background.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import androidx.work.OutOfQuotaPolicy
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.background.domain.Constraints
import io.kmp.taskmanager.background.domain.ExistingPolicy
import io.kmp.taskmanager.background.domain.ScheduleResult
import io.kmp.taskmanager.background.domain.TaskTrigger
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

import androidx.work.OneTimeWorkRequestBuilder

/**
 * The `actual` implementation for the Android platform.
 * This class acts as a bridge between the shared KMP domain logic and the
 * native Android WorkManager API.
 *
 * **Extensibility**: For exact alarms or custom scheduling needs, extend this class
 * and override the relevant methods.
 */
open actual class NativeTaskScheduler(private val context: Context) : BackgroundTaskScheduler {

    private val workManager = WorkManager.getInstance(context)

    companion object {
        const val TAG_KMP_TASK = "KMP_TASK"
    }

    /**
     * Enqueues a task based on its trigger type.
     * - `Periodic` triggers use WorkManager for efficient, deferrable background work.
     * - `Exact` triggers use AlarmManager for time-critical, user-facing events like reminders.
     * - **v3.0.0+**: Deprecated triggers (BatteryLow, StorageLow, etc.) are auto-converted to SystemConstraints
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

        // Handle deprecated triggers with auto-conversion
        val (actualTrigger, updatedConstraints) = mapLegacyTrigger(trigger, constraints)

        return when (actualTrigger) {
            is TaskTrigger.Periodic -> {
                schedulePeriodicWork(id, actualTrigger, workerClassName, updatedConstraints, inputJson, policy)
            }

            is TaskTrigger.Exact -> {
                scheduleExactAlarm(id, actualTrigger, workerClassName, inputJson)
            }

            is TaskTrigger.Windowed -> {
                Logger.w(LogTags.SCHEDULER, "Windowed triggers not yet implemented on Android")
                ScheduleResult.REJECTED_OS_POLICY
            }

            is TaskTrigger.OneTime -> {
                scheduleOneTimeWork(id, actualTrigger, workerClassName, updatedConstraints, inputJson, policy)
            }

            is TaskTrigger.ContentUri -> {
                scheduleContentUriWork(id, actualTrigger, workerClassName, updatedConstraints, inputJson, policy)
            }

            // Deprecated triggers should have been converted by mapLegacyTrigger
            TaskTrigger.StorageLow,
            TaskTrigger.BatteryLow,
            TaskTrigger.BatteryOkay,
            TaskTrigger.DeviceIdle -> {
                Logger.e(LogTags.SCHEDULER, "Deprecated trigger ${trigger::class.simpleName} reached unreachable code - this is a bug")
                ScheduleResult.REJECTED_OS_POLICY
            }
        }
    }

    /**
     * Maps legacy deprecated triggers to SystemConstraints (v3.0.0+ backward compatibility)
     * @return Pair of (converted trigger, updated constraints)
     */
    private fun mapLegacyTrigger(
        trigger: TaskTrigger,
        constraints: Constraints
    ): Pair<TaskTrigger, Constraints> {
        return when (trigger) {
            TaskTrigger.StorageLow -> {
                Logger.w(LogTags.SCHEDULER, "⚠️ DEPRECATED: TaskTrigger.StorageLow. Use Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))")
                TaskTrigger.OneTime() to constraints.copy(
                    systemConstraints = constraints.systemConstraints + io.kmp.taskmanager.background.domain.SystemConstraint.ALLOW_LOW_STORAGE
                )
            }

            TaskTrigger.BatteryLow -> {
                Logger.w(LogTags.SCHEDULER, "⚠️ DEPRECATED: TaskTrigger.BatteryLow. Use Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))")
                TaskTrigger.OneTime() to constraints.copy(
                    systemConstraints = constraints.systemConstraints + io.kmp.taskmanager.background.domain.SystemConstraint.ALLOW_LOW_BATTERY
                )
            }

            TaskTrigger.BatteryOkay -> {
                Logger.w(LogTags.SCHEDULER, "⚠️ DEPRECATED: TaskTrigger.BatteryOkay. Use Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))")
                TaskTrigger.OneTime() to constraints.copy(
                    systemConstraints = constraints.systemConstraints + io.kmp.taskmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW
                )
            }

            TaskTrigger.DeviceIdle -> {
                Logger.w(LogTags.SCHEDULER, "⚠️ DEPRECATED: TaskTrigger.DeviceIdle. Use Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))")
                TaskTrigger.OneTime() to constraints.copy(
                    systemConstraints = constraints.systemConstraints + io.kmp.taskmanager.background.domain.SystemConstraint.DEVICE_IDLE
                )
            }

            // Not a legacy trigger, return as-is
            else -> trigger to constraints
        }
    }

    /**
     * Builds WorkManager Constraints from KMP Constraints (v3.0.0+ with SystemConstraints support)
     */
    private fun buildWorkManagerConstraints(constraints: Constraints): androidx.work.Constraints {
        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        val builder = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)

        // Apply systemConstraints (v3.0.0+)
        constraints.systemConstraints.forEach { systemConstraint ->
            when (systemConstraint) {
                io.kmp.taskmanager.background.domain.SystemConstraint.ALLOW_LOW_STORAGE -> {
                    builder.setRequiresStorageNotLow(false) // Allow when storage IS low
                }
                io.kmp.taskmanager.background.domain.SystemConstraint.ALLOW_LOW_BATTERY -> {
                    builder.setRequiresBatteryNotLow(false) // Allow when battery IS low
                }
                io.kmp.taskmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW -> {
                    builder.setRequiresBatteryNotLow(true) // Require battery NOT low
                }
                io.kmp.taskmanager.background.domain.SystemConstraint.DEVICE_IDLE -> {
                    builder.setRequiresDeviceIdle(true) // Require device idle/dozing
                }
            }
        }

        return builder.build()
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

        // Build WorkManager constraints (v3.0.0+ with SystemConstraints support)
        val wmConstraints = buildWorkManagerConstraints(constraints)

        val workRequest = PeriodicWorkRequestBuilder<KmpWorker>(
            trigger.intervalMs,
            TimeUnit.MILLISECONDS
        )
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .setBackoffCriteria(
                if (constraints.backoffPolicy == io.kmp.taskmanager.background.domain.BackoffPolicy.EXPONENTIAL)
                    BackoffPolicy.EXPONENTIAL
                else
                    BackoffPolicy.LINEAR,
                constraints.backoffDelayMs,
                TimeUnit.MILLISECONDS
            )
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
     * Schedules an exact alarm with automatic fallback to WorkManager.
     *
     * **v3.0.0+ Behavior:**
     * - Android 12+ (API 31+): Checks `SCHEDULE_EXACT_ALARM` permission
     * - If permission granted: Uses AlarmManager for exact scheduling
     * - If permission denied: Falls back to WorkManager OneTime task with delay
     *
     * **For custom AlarmReceiver:**
     * Override `getAlarmReceiverClass()` to return your BroadcastReceiver class.
     *
     * **Example:**
     * ```kotlin
     * class MyScheduler(context: Context) : NativeTaskScheduler(context) {
     *     override fun getAlarmReceiverClass(): Class<out AlarmReceiver> = MyAlarmReceiver::class.java
     * }
     * ```
     */
    protected open fun scheduleExactAlarm(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        inputJson: String?
    ): ScheduleResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check permission for Android 12+ (API 31+)
        val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Pre-Android 12 doesn't require permission
        }

        if (!canScheduleExactAlarms) {
            Logger.w(LogTags.ALARM, """
                ⚠️ SCHEDULE_EXACT_ALARM permission denied (Android 12+)
                Falling back to WorkManager OneTime task with ${trigger.atEpochMillis}ms delay.

                To use exact alarms, add to AndroidManifest.xml:
                <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

                And request permission:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                }
            """.trimIndent())

            // Fallback: Use WorkManager with delay
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = trigger.atEpochMillis),
                workerClassName = workerClassName,
                constraints = Constraints(), // No constraints for fallback
                inputJson = inputJson,
                policy = ExistingPolicy.REPLACE
            )
        }

        // Get AlarmReceiver class from user implementation
        val receiverClass = getAlarmReceiverClass()
        if (receiverClass == null) {
            Logger.e(LogTags.ALARM, """
                ❌ No AlarmReceiver class provided!
                Override getAlarmReceiverClass() to return your BroadcastReceiver.

                Example:
                class MyScheduler(context: Context) : NativeTaskScheduler(context) {
                    override fun getAlarmReceiverClass() = MyAlarmReceiver::class.java
                }

                Falling back to WorkManager...
            """.trimIndent())

            // Fallback to WorkManager
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = trigger.atEpochMillis),
                workerClassName = workerClassName,
                constraints = Constraints(),
                inputJson = inputJson,
                policy = ExistingPolicy.REPLACE
            )
        }

        // Create PendingIntent for AlarmReceiver
        val intent = Intent(context, receiverClass).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, id)
            putExtra(AlarmReceiver.EXTRA_WORKER_CLASS, workerClassName)
            inputJson?.let { putExtra(AlarmReceiver.EXTRA_INPUT_JSON, it) }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(), // Use ID hash as request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule exact alarm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.atEpochMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    trigger.atEpochMillis,
                    pendingIntent
                )
            }

            Logger.i(LogTags.ALARM, "✅ Scheduled exact alarm - ID: '$id', Time: ${trigger.atEpochMillis}ms, Receiver: ${receiverClass.simpleName}")
            return ScheduleResult.ACCEPTED

        } catch (e: SecurityException) {
            Logger.e(LogTags.ALARM, "❌ SecurityException scheduling exact alarm", e)

            // Final fallback to WorkManager
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = trigger.atEpochMillis),
                workerClassName = workerClassName,
                constraints = Constraints(),
                inputJson = inputJson,
                policy = ExistingPolicy.REPLACE
            )
        }
    }

    /**
     * Override this method to provide your custom AlarmReceiver class.
     *
     * **v3.0.0+**: Return your BroadcastReceiver that extends AlarmReceiver.
     *
     * @return Your AlarmReceiver class, or null to use WorkManager fallback
     */
    protected open fun getAlarmReceiverClass(): Class<out AlarmReceiver>? {
        return null
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

        val wmConstraints = buildWorkManagerConstraints(constraints)
        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "one-time", trigger.initialDelayMs, wmConstraints
        )

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

        // Build constraints with ContentUriTrigger
        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.addContentUriTrigger(
                android.net.Uri.parse(trigger.uriString),
                trigger.triggerForDescendants
            )
        }

        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "content-uri", 0L, wmConstraints
        )

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

        // Note: WorkManager has setRequiresStorageNotLow, but we want to trigger ON low storage
        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.setRequiresStorageNotLow(false) // Allow running when storage is low
        }

        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "storage-low", 0L, wmConstraints
        )

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

        // Note: WorkManager has setRequiresBatteryNotLow, but we want to trigger ON low battery
        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.setRequiresBatteryNotLow(false) // Allow running when battery is low
        }

        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "battery-low", 0L, wmConstraints
        )

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

        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.setRequiresBatteryNotLow(true) // Only run when battery is NOT low
        }

        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "battery-okay", 0L, wmConstraints
        )

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

        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.setRequiresDeviceIdle(true) // Only run when device is idle
        }

        val workRequest = buildOneTimeWorkRequest(
            id, workerClassName, constraints, inputJson,
            "device-idle", 0L, wmConstraints
        )

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        Logger.i(LogTags.SCHEDULER, "Successfully enqueued DeviceIdle task '$id'")
        return ScheduleResult.ACCEPTED
    }

    /**
     * Helper: Build common Constraints for WorkManager from KMP Constraints
     */
    private fun buildWorkManagerConstraints(
        constraints: Constraints,
        extraConfig: (androidx.work.Constraints.Builder) -> Unit = {}
    ): androidx.work.Constraints {
        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }

        return androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .apply { extraConfig(this) }
            .build()
    }

    /**
     * Helper: Build common OneTimeWorkRequest with standard configuration
     */
    private fun buildOneTimeWorkRequest(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        taskType: String,
        initialDelayMs: Long = 0L,
        wmConstraints: androidx.work.Constraints
    ): OneTimeWorkRequest {
        val workData = Data.Builder()
            .putString("workerClassName", workerClassName)
            .apply { inputJson?.let { putString("inputJson", it) } }
            .build()

        val workRequestBuilder = OneTimeWorkRequestBuilder<KmpWorker>()
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .setBackoffCriteria(
                if (constraints.backoffPolicy == io.kmp.taskmanager.background.domain.BackoffPolicy.EXPONENTIAL)
                    BackoffPolicy.EXPONENTIAL
                else
                    BackoffPolicy.LINEAR,
                constraints.backoffDelayMs,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_KMP_TASK)
            .addTag("type-$taskType")
            .addTag("id-$id")
            .addTag("worker-$workerClassName")

        // Apply expedited if NOT a heavy task (for faster execution of light tasks)
        return if (!constraints.isHeavyTask) {
            Logger.d(LogTags.SCHEDULER, "Creating EXPEDITED task for faster execution")
            workRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        } else {
            Logger.d(LogTags.SCHEDULER, "Creating REGULAR task for heavy processing")
            workRequestBuilder.build()
        }
    }

    /**
     * Cancels a task by its unique ID.
     *
     * **v3.0.0+**: Cancels both WorkManager tasks and exact alarms.
     */
    actual override fun cancel(id: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling task with ID '$id'")

        // Cancel WorkManager task
        workManager.cancelUniqueWork(id)
        Logger.d(LogTags.SCHEDULER, "Cancelled WorkManager task '$id'")

        // Also cancel exact alarm if one exists
        val receiverClass = getAlarmReceiverClass()
        if (receiverClass != null) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, receiverClass)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Logger.d(LogTags.ALARM, "Cancelled exact alarm for task '$id'")
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Error cancelling exact alarm for task '$id'", e)
            }
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

    actual override fun beginWith(task: io.kmp.taskmanager.background.domain.TaskRequest): io.kmp.taskmanager.background.domain.TaskChain {
        return io.kmp.taskmanager.background.domain.TaskChain(this, listOf(task))
    }

    actual override fun beginWith(tasks: List<io.kmp.taskmanager.background.domain.TaskRequest>): io.kmp.taskmanager.background.domain.TaskChain {
        return io.kmp.taskmanager.background.domain.TaskChain(this, tasks)
    }

    actual override fun enqueueChain(chain: io.kmp.taskmanager.background.domain.TaskChain) {
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

    private fun createWorkRequest(task: io.kmp.taskmanager.background.domain.TaskRequest): OneTimeWorkRequest {
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
            OneTimeWorkRequestBuilder<KmpWorker>()
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