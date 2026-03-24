@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class) // androidMain intentionally handles Android-only triggers (ContentUri, SystemConstraint)

package dev.brewkits.kmpworkmanager.background.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import androidx.work.OutOfQuotaPolicy
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
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
open class NativeTaskScheduler(private val context: Context) : BackgroundTaskScheduler {

    private val workManager = WorkManager.getInstance(context)

    companion object {
        const val TAG_KMP_TASK = "KMP_TASK"

        /**
         * WorkManager's hard limit for `Data` payload size is 10 240 bytes (10 KB).
         * We use 8 KB as the overflow threshold to leave headroom for other keys
         * (`workerClassName`, tags) and serialization overhead.
         */
        private const val OVERFLOW_THRESHOLD_BYTES = 8_192

        /**
         * Key used to pass the path of an overflow input-JSON temp file.
         * Workers check for this key first and read the file contents as `inputJson`.
         */
        internal const val KEY_INPUT_JSON_FILE = "inputJsonFile"
    }

    /**
     * Builds a WorkManager [Data] payload for the given worker and input.
     *
     * **10 KB overflow guard:**
     * WorkManager throws [IllegalStateException] at enqueue time when the total `Data`
     * size exceeds 10 240 bytes. Large `inputJson` values (e.g. a list of 100 objects,
     * a Base64-encoded image) would crash the host app.
     *
     * Strategy:
     * - ≤ 8 KB  → pass `inputJson` inline via `Data` (fast path, no I/O)
     * - > 8 KB  → write the JSON to a temp file in `cacheDir`, pass only the file path.
     *             Workers call [resolveInputJson] to transparently read and delete the file.
     */
    private fun buildWorkData(workerClassName: String, inputJson: String?): Data {
        val builder = Data.Builder().putString("workerClassName", workerClassName)

        if (inputJson == null) return builder.build()

        val bytes = inputJson.encodeToByteArray()
        return if (bytes.size <= OVERFLOW_THRESHOLD_BYTES) {
            builder.putString("inputJson", inputJson).build()
        } else {
            // Overflow: spill to a temp file. Name includes timestamp + hash to avoid collisions.
            val tempFile = java.io.File(
                context.cacheDir,
                "kmp_input_${System.currentTimeMillis()}_${workerClassName.hashCode()}.json"
            )
            try {
                tempFile.writeText(inputJson)
                Logger.d(
                    LogTags.SCHEDULER,
                    "Input JSON overflow (${bytes.size}B > ${OVERFLOW_THRESHOLD_BYTES}B) — " +
                        "spilled to temp file: ${tempFile.name}"
                )
                builder.putString(KEY_INPUT_JSON_FILE, tempFile.absolutePath).build()
            } catch (e: Exception) {
                Logger.e(
                    LogTags.SCHEDULER,
                    "Failed to spill overflow JSON to cacheDir — truncating to ${OVERFLOW_THRESHOLD_BYTES}B",
                    e
                )
                // Last-resort truncation so enqueue doesn't crash the host app.
                builder.putString("inputJson", inputJson.take(OVERFLOW_THRESHOLD_BYTES)).build()
            }
        }
    }

    // Maps KMP BackoffPolicy → WorkManager BackoffPolicy.
    // Extracted to avoid repeating the same conditional in three builder sites.
    private fun dev.brewkits.kmpworkmanager.background.domain.Constraints.toWorkManagerBackoffPolicy(): BackoffPolicy =
        if (backoffPolicy == dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy.EXPONENTIAL)
            BackoffPolicy.EXPONENTIAL
        else
            BackoffPolicy.LINEAR

    /**
     * Enqueues a task based on its trigger type.
     * - `Periodic` triggers use WorkManager for efficient, deferrable background work.
     * - `Exact` triggers use AlarmManager for time-critical, user-facing events like reminders.
     * - Deprecated triggers (BatteryLow, StorageLow, etc.) are auto-converted to SystemConstraints
     */
    override suspend fun enqueue(
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
                scheduleExactAlarm(id, actualTrigger, workerClassName, updatedConstraints, inputJson)
            }

            is TaskTrigger.Windowed -> {
                // Deadline guard: reject immediately if the window has already closed.
                val nowMs = System.currentTimeMillis()
                if (actualTrigger.latest < nowMs) {
                    Logger.e(
                        LogTags.SCHEDULER,
                        "❌ Windowed task '$id' DEADLINE_ALREADY_PASSED — latest=${actualTrigger.latest}ms is in the past " +
                            "by ${nowMs - actualTrigger.latest}ms. The business window is gone; the task will not be scheduled."
                    )
                    return ScheduleResult.DEADLINE_ALREADY_PASSED
                }

                // Android has no native "window" concept. Map to OneTime with a delay to the
                // earliest start time. The 'latest' deadline is not OS-enforced — warn the caller.
                val delayMs = maxOf(0L, actualTrigger.earliest - nowMs)
                val windowMinutes = (actualTrigger.latest - maxOf(actualTrigger.earliest, nowMs)) / 60_000
                if (windowMinutes < 30) {
                    Logger.w(
                        LogTags.SCHEDULER,
                        "⚠️ Windowed task '$id' has a tight window (~${windowMinutes}min). " +
                            "Android WorkManager does not enforce 'latest' — the task may run after the deadline. " +
                            "Use TaskTrigger.Exact for deadline-critical work."
                    )
                } else {
                    Logger.w(
                        LogTags.SCHEDULER,
                        "Windowed trigger '$id': 'latest' deadline not enforced on Android. " +
                            "Scheduling as OneTime with ${delayMs}ms delay to earliest."
                    )
                }
                scheduleOneTimeWork(
                    id, TaskTrigger.OneTime(initialDelayMs = delayMs),
                    workerClassName, updatedConstraints, inputJson, policy
                )
            }

            is TaskTrigger.OneTime -> {
                scheduleOneTimeWork(id, actualTrigger, workerClassName, updatedConstraints, inputJson, policy)
            }

            is TaskTrigger.ContentUri -> {
                scheduleContentUriWork(id, actualTrigger, workerClassName, updatedConstraints, inputJson, policy)
            }

        }
    }

    /**
     * Maps legacy deprecated triggers to SystemConstraints
     * @return Pair of (converted trigger, updated constraints)
     */
    private fun mapLegacyTrigger(
        trigger: TaskTrigger,
        constraints: Constraints
    ): Pair<TaskTrigger, Constraints> = trigger to constraints

    /**
     * Builds WorkManager Constraints from KMP Constraints.
     * Applies network, charging, systemConstraints, and any extra configuration via [extraConfig].
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

        val builder = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)

        // Apply systemConstraints
        constraints.systemConstraints.forEach { systemConstraint ->
            when (systemConstraint) {
                dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.ALLOW_LOW_STORAGE -> {
                    builder.setRequiresStorageNotLow(false)
                }
                dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.ALLOW_LOW_BATTERY -> {
                    builder.setRequiresBatteryNotLow(false)
                }
                dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW -> {
                    builder.setRequiresBatteryNotLow(true)
                }
                dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.DEVICE_IDLE -> {
                    builder.setRequiresDeviceIdle(true)
                }
            }
        }

        extraConfig(builder)
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

        // Pass the target worker's class name and any input data to the KmpWorker.
        // buildWorkData() handles the 10KB Data limit automatically.
        val workData = buildWorkData(workerClassName, inputJson)

        // Build WorkManager constraints
        val wmConstraints = buildWorkManagerConstraints(constraints)

        val periodicBuilder = if (trigger.flexMs != null) {
            PeriodicWorkRequestBuilder<KmpWorker>(
                trigger.intervalMs, TimeUnit.MILLISECONDS,
                trigger.flexMs, TimeUnit.MILLISECONDS
            )
        } else {
            PeriodicWorkRequestBuilder<KmpWorker>(
                trigger.intervalMs, TimeUnit.MILLISECONDS
            )
        }

        val workRequest = periodicBuilder
            .setConstraints(wmConstraints)
            .setInputData(workData)
            .setBackoffCriteria(
                constraints.toWorkManagerBackoffPolicy(),
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
     * **Behavior:**
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
        constraints: Constraints,
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

            // Fallback: Use WorkManager with delay (preserve constraints)
            val delayMs = (trigger.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = delayMs),
                workerClassName = workerClassName,
                constraints = constraints, // Preserve constraints from user
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

            // Fallback to WorkManager: compute actual delay from now, not raw epoch
            val delayMs = (trigger.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = delayMs),
                workerClassName = workerClassName,
                constraints = constraints,
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

            // Final fallback to WorkManager: compute actual delay from now, not raw epoch
            val delayMs = (trigger.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
            return scheduleOneTimeWork(
                id = id,
                trigger = TaskTrigger.OneTime(initialDelayMs = delayMs),
                workerClassName = workerClassName,
                constraints = constraints,
                inputJson = inputJson,
                policy = ExistingPolicy.REPLACE
            )
        }
    }

    /**
     * Override this method to provide your custom AlarmReceiver class.
     *
     * Return your BroadcastReceiver that extends AlarmReceiver.
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
        val workData = buildWorkData(workerClassName, inputJson)

        // Use KmpHeavyWorker for heavy tasks (foreground service with notification)
        // Use KmpWorker for regular tasks (background execution)
        return if (constraints.isHeavyTask) {
            Logger.d(LogTags.SCHEDULER, "Creating HEAVY task with foreground service")
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(wmConstraints)
                .setInputData(workData)
                .setBackoffCriteria(
                    constraints.toWorkManagerBackoffPolicy(),
                    constraints.backoffDelayMs,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_KMP_TASK)
                .addTag("type-$taskType")
                .addTag("id-$id")
                .addTag("worker-$workerClassName")
                .build()
        } else {
            // Expedited work restrictions (WorkManager):
            // 1. Cannot have delay
            // 2. Only supports network and storage constraints — charging, device-idle,
            //    and battery constraints are incompatible with expedited mode
            val hasIncompatibleConstraints = constraints.requiresCharging ||
                    constraints.systemConstraints.any {
                        it == dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.DEVICE_IDLE ||
                        it == dev.brewkits.kmpworkmanager.background.domain.SystemConstraint.REQUIRE_BATTERY_NOT_LOW
                    }

            val shouldUseExpedited = initialDelayMs == 0L && !hasIncompatibleConstraints

            Logger.d(LogTags.SCHEDULER, "Creating ${if (shouldUseExpedited) "EXPEDITED" else "REGULAR"} task (delay: ${initialDelayMs}ms, incompatibleConstraints: $hasIncompatibleConstraints)")

            val builder = OneTimeWorkRequestBuilder<KmpWorker>()
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(wmConstraints)
                .setInputData(workData)
                .setBackoffCriteria(
                    constraints.toWorkManagerBackoffPolicy(),
                    constraints.backoffDelayMs,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_KMP_TASK)
                .addTag("type-$taskType")
                .addTag("id-$id")
                .addTag("worker-$workerClassName")

            // Only set expedited policy when compatible
            if (shouldUseExpedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            builder.build()
        }
    }

    /**
     * Cancels a task by its unique ID.
     *
     * Cancels both WorkManager tasks and exact alarms.
     */
    override fun cancel(id: String) {
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
     * Cancels all tasks scheduled by this library.
     *
     * Only cancels work tagged with [TAG_KMP_TASK] — host-app WorkManager tasks are unaffected.
     * Exact alarms (AlarmManager) must be cancelled individually via [cancel].
     */
    override fun cancelAll() {
        Logger.w(LogTags.SCHEDULER, "Cancelling all KMP-managed background tasks (tag: $TAG_KMP_TASK)")
        workManager.cancelAllWorkByTag(TAG_KMP_TASK)
        Logger.d(LogTags.SCHEDULER, "Cancelled all KMP WorkManager tasks (alarms require individual cancellation)")
    }

    /**
     * Flush all pending progress updates immediately.
     * Implementation for Android.
     *
     * **Note for Android:**
     * WorkManager automatically persists progress updates via Room database.
     * No explicit flush needed on Android - this is a no-op for API consistency.
     *
     * Progress is already durable on Android through:
     * - Room database with WAL mode (write-ahead logging)
     * - Automatic transaction management by WorkManager
     * - Crash-safe persistence
     */
    override fun flushPendingProgress() {
        // No-op on Android: WorkManager handles progress persistence automatically
        Logger.v(LogTags.SCHEDULER, "flushPendingProgress called (no-op on Android - WorkManager auto-persists)")
    }

    override fun beginWith(task: dev.brewkits.kmpworkmanager.background.domain.TaskRequest): dev.brewkits.kmpworkmanager.background.domain.TaskChain {
        return dev.brewkits.kmpworkmanager.background.domain.TaskChain(this, listOf(task))
    }

    override fun beginWith(tasks: List<dev.brewkits.kmpworkmanager.background.domain.TaskRequest>): dev.brewkits.kmpworkmanager.background.domain.TaskChain {
        return dev.brewkits.kmpworkmanager.background.domain.TaskChain(this, tasks)
    }

    /**
     * Enqueues a task chain for execution.
     *
     * Now suspending for consistency with iOS implementation.
     * Android WorkManager handles the async nature internally.
     */
    override suspend fun enqueueChain(
        chain: dev.brewkits.kmpworkmanager.background.domain.TaskChain,
        id: String?,
        policy: dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
    ) {
        // Note: WorkManager handles existing work policy natively
        // id and policy parameters are included for cross-platform compatibility

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

    private fun createWorkRequest(task: dev.brewkits.kmpworkmanager.background.domain.TaskRequest): OneTimeWorkRequest {
        val workData = Data.Builder()
            .putString("workerClassName", task.workerClassName)
            .apply { task.inputJson?.let { putString("inputJson", it) } }
            .build()

        val constraints = task.constraints

        val wmConstraints = if (constraints != null) {
            buildWorkManagerConstraints(constraints)
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