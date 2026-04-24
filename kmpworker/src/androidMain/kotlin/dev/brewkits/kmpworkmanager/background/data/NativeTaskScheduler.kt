package dev.brewkits.kmpworkmanager.background.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder

/**
 * Android implementation of BackgroundTaskScheduler using WorkManager.
 */
open class NativeTaskScheduler(private val context: Context) : BackgroundTaskScheduler {

    private val workManager = WorkManager.getInstance(context)

    companion object {
        const val TAG_KMP_TASK = "kmp-worker-task"
        const val KEY_INPUT_JSON_FILE = "inputJsonFile"
        internal const val OVERFLOW_THRESHOLD_BYTES = 8192 // 8 KB
        private const val ZOMBIE_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Scans `cacheDir` for overflow input-JSON files (`kmp_input_*.json`) older than
         * [ZOMBIE_FILE_MAX_AGE_MS] and deletes them.
         *
         * Uses CoroutineScope with Dispatchers.IO to reuse system threads.
         */
        fun cleanupZombieInputFiles(context: Context) {
            val cacheDir = context.cacheDir
            val cutoffMs = System.currentTimeMillis() - ZOMBIE_FILE_MAX_AGE_MS
            
            // Re-use I/O thread pool via Coroutines
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val zombies = cacheDir.listFiles { file ->
                        file.name.startsWith("kmp_input_") &&
                            file.name.endsWith(".json") &&
                            file.lastModified() < cutoffMs
                    } ?: return@launch

                    var deleted = 0
                    var freed = 0L
                    for (file in zombies) {
                        freed += file.length()
                        if (file.delete()) deleted++
                    }

                    if (deleted > 0) {
                        Logger.i(LogTags.SCHEDULER, "Cleaned up $deleted zombie overflow file(s), freed ${freed / 1024}KB")
                    }
                } catch (e: Exception) {
                    Logger.w(LogTags.SCHEDULER, "Zombie file cleanup failed: ${e.message}")
                }
            }
        }
    }

    @OptIn(AndroidOnly::class)
    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        val result = when (trigger) {
            is TaskTrigger.Exact -> scheduleExactAlarm(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Periodic -> schedulePeriodicWork(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.ContentUri -> scheduleContentUriWork(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Windowed -> {
                val now = System.currentTimeMillis()
                if (trigger.latest < now) {
                    Logger.w(LogTags.SCHEDULER, "Windowed task '$id' rejected: deadline already passed")
                    ScheduleResult.DEADLINE_ALREADY_PASSED
                } else {
                    val delayMs = (trigger.earliest - now).coerceAtLeast(0L)
                    val updatedConstraints = constraints.copy()
                    scheduleOneTimeWork(
                        id, TaskTrigger.OneTime(initialDelayMs = delayMs),
                        workerClassName, updatedConstraints, inputJson, policy
                    )
                }
            }
            is TaskTrigger.OneTime -> scheduleOneTimeWork(id, trigger, workerClassName, constraints, inputJson, policy)
        }

        if (result == ScheduleResult.ACCEPTED) {
            val delayMs = when (trigger) {
                is TaskTrigger.OneTime -> trigger.initialDelayMs
                is TaskTrigger.Periodic -> trigger.initialDelayMs
                is TaskTrigger.Windowed -> (trigger.earliest - System.currentTimeMillis()).coerceAtLeast(0L)
                is TaskTrigger.Exact -> (trigger.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                else -> 0L
            }
            KmpWorkManagerRuntime.notifyTaskScheduled(
                TelemetryHook.TaskScheduledEvent(
                    taskId = id,
                    taskName = workerClassName,
                    triggerType = trigger::class.simpleName ?: "Unknown",
                    initialDelayMs = delayMs,
                    platform = "android"
                )
            )
        }
        
        return result
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
        val workRequest = try {
            Logger.d(LogTags.SCHEDULER, "Building request for $workerClassName")
            buildOneTimeWorkRequest(
                id, workerClassName, constraints, inputJson,
                "one-time", trigger.initialDelayMs, wmConstraints
            )
        } catch (e: IllegalArgumentException) {
            Logger.e(LogTags.SCHEDULER, "Rejecting one-time task '$id': ${e.message}", e)
            return ScheduleResult.REJECTED_OS_POLICY
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "Rejecting one-time task '$id' due to unexpected error", e)
            return ScheduleResult.REJECTED_OS_POLICY
        }

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        return ScheduleResult.ACCEPTED
    }

    private fun schedulePeriodicWork(
        id: String,
        trigger: TaskTrigger.Periodic,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        val intervalMs = trigger.intervalMs
        // WorkManager requires flexMs >= 5 min. Default to half the interval when not specified.
        // Clamp between the OS minimum and the interval (flex > interval is nonsensical).
        val effectiveFlexMs = (trigger.flexMs ?: (intervalMs / 2))
            .coerceAtLeast(5 * 60 * 1000L)
            .coerceAtMost(intervalMs)

        // When runImmediately = false and no explicit delay is set, defer first run by one
        // full interval. This eliminates the workaround of setting initialDelayMs = intervalMs.
        val effectiveInitialDelayMs = if (!trigger.runImmediately && trigger.initialDelayMs == 0L) {
            intervalMs
        } else {
            trigger.initialDelayMs
        }

        Logger.i(LogTags.SCHEDULER, "Scheduling periodic task - ID: '$id', Interval: ${intervalMs}ms, " +
            "Flex: ${effectiveFlexMs}ms, EffectiveInitialDelay: ${effectiveInitialDelayMs}ms, runImmediately: ${trigger.runImmediately}")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> androidx.work.ExistingPeriodicWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> androidx.work.ExistingPeriodicWorkPolicy.REPLACE
        }

        val wmConstraints = buildWorkManagerConstraints(constraints)

        val workData = try {
            buildPeriodicWorkData(workerClassName, inputJson)
        } catch (e: IllegalArgumentException) {
            Logger.e(LogTags.SCHEDULER, "Rejecting periodic task '$id': ${e.message}")
            return ScheduleResult.REJECTED_OS_POLICY
        }

        val builder = PeriodicWorkRequestBuilder<KmpWorker>(
            intervalMs, TimeUnit.MILLISECONDS,
            effectiveFlexMs, TimeUnit.MILLISECONDS
        )
            .setInitialDelay(effectiveInitialDelayMs, TimeUnit.MILLISECONDS)
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

        workManager.enqueueUniquePeriodicWork(id, workManagerPolicy, builder.build())
        return ScheduleResult.ACCEPTED
    }

    @OptIn(AndroidOnly::class)
    private fun scheduleContentUriWork(
        id: String,
        trigger: TaskTrigger.ContentUri,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

        val wmConstraints = buildWorkManagerConstraints(constraints) { builder ->
            builder.addContentUriTrigger(
                android.net.Uri.parse(trigger.uriString),
                trigger.triggerForDescendants
            )
        }

        val workRequest = try {
            buildOneTimeWorkRequest(
                id, workerClassName, constraints, inputJson,
                "content-uri", 0L, wmConstraints
            )
        } catch (e: IllegalArgumentException) {
            return ScheduleResult.REJECTED_OS_POLICY
        }

        workManager.enqueueUniqueWork(id, workManagerPolicy, workRequest)
        return ScheduleResult.ACCEPTED
    }

    @OptIn(AndroidOnly::class)
    private fun scheduleExactAlarm(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        if (policy == ExistingPolicy.KEEP && AlarmStore.getFutureAlarms(context).any { it.id == id }) {
            return ScheduleResult.ACCEPTED
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canSchedule = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

        Logger.i(LogTags.ALARM, "Package: ${context.packageName}, canSchedule: $canSchedule")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !canSchedule) {
            Logger.e(LogTags.ALARM, "SCHEDULE_EXACT_ALARM permission not granted — rejecting exact alarm for '$id'")
            return ScheduleResult.REJECTED_OS_POLICY
        }

        val receiverClass = getAlarmReceiverClass() ?: return ScheduleResult.REJECTED_OS_POLICY
        
        val intent = Intent(context, receiverClass).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, id)
            putExtra(AlarmReceiver.EXTRA_WORKER_CLASS, workerClassName)
            
            if (inputJson != null) {
                val bytes = inputJson.encodeToByteArray()
                if (bytes.size <= OVERFLOW_THRESHOLD_BYTES) {
                    putExtra(AlarmReceiver.EXTRA_INPUT_JSON, inputJson)
                } else {
                    val tempFile = java.io.File(context.cacheDir, "kmp_input_${java.util.UUID.randomUUID()}.json")
                    try {
                        tempFile.bufferedWriter().use { it.write(inputJson) }
                        putExtra(AlarmReceiver.EXTRA_INPUT_JSON_FILE, tempFile.absolutePath)
                    } catch (e: Exception) {
                        return ScheduleResult.REJECTED_OS_POLICY
                    }
                }
            }
        }

        // Cancel any stale PendingIntent first (idempotency guard — handles divergence between
        // AlarmStore and AlarmManager state, e.g. after AlarmStore clear without AlarmManager cancel).
        cancelAlarmManagerPendingIntent(id)

        val pendingIntent = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        AlarmStore.save(context, AlarmStore.AlarmMetadata(id, trigger.atEpochMillis, workerClassName, inputJson))
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.atEpochMillis, pendingIntent)
        
        return ScheduleResult.ACCEPTED
    }

    private fun buildOneTimeWorkRequest(
        id: String,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        taskType: String,
        initialDelayMs: Long,
        wmConstraints: androidx.work.Constraints
    ): OneTimeWorkRequest {
        val workData = buildWorkData(workerClassName, inputJson)
        val builder = if (constraints.isHeavyTask) {
            OneTimeWorkRequestBuilder<KmpHeavyWorker>()
        } else {
            OneTimeWorkRequestBuilder<KmpWorker>()
        }

        builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
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

        if (initialDelayMs == 0L && !constraints.isHeavyTask) {
            // Expedited work does not support some constraints like charging.
            // Safe to set only if it's a simple urgent task.
            if (!constraints.requiresCharging && !constraints.requiresUnmeteredNetwork) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }

        return builder.build()
    }

    private fun buildWorkData(workerClassName: String, inputJson: String?): Data {
        val builder = Data.Builder().putString("workerClassName", workerClassName)
        if (inputJson == null) return builder.build()

        val bytes = inputJson.encodeToByteArray()
        // WorkManager data limit is 10KB. We set threshold to 8KB to be safe.
        return if (bytes.size <= OVERFLOW_THRESHOLD_BYTES) {
            builder.putString("inputJson", inputJson).build()
        } else {
            val tempFile = java.io.File(context.cacheDir, "kmp_input_${java.util.UUID.randomUUID()}.json")
            try {
                tempFile.bufferedWriter().use { it.write(inputJson) }
                builder.putString(KEY_INPUT_JSON_FILE, tempFile.absolutePath).build()
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Failed to spill overflow JSON to cacheDir", e)
                throw IllegalArgumentException("JSON too large and failed to spill to disk", e)
            }
        }
    }

    private fun buildPeriodicWorkData(workerClassName: String, inputJson: String?): Data {
        val builder = Data.Builder().putString("workerClassName", workerClassName)
        if (inputJson == null) return builder.build()

        val bytes = inputJson.encodeToByteArray()
        if (bytes.size > OVERFLOW_THRESHOLD_BYTES) {
            throw IllegalArgumentException("Periodic task input too large (> 8KB). WorkManager reuses Data, so disk spill is unsafe.")
        }
        return builder.putString("inputJson", inputJson).build()
    }

    @OptIn(AndroidOnly::class)
    private fun buildWorkManagerConstraints(
        constraints: Constraints,
        block: ((androidx.work.Constraints.Builder) -> Unit)? = null
    ): androidx.work.Constraints {
        val builder = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(if (constraints.requiresUnmeteredNetwork) androidx.work.NetworkType.UNMETERED 
                                   else if (constraints.requiresNetwork) androidx.work.NetworkType.CONNECTED 
                                   else androidx.work.NetworkType.NOT_REQUIRED)
            .setRequiresCharging(constraints.requiresCharging)
        
        constraints.systemConstraints.forEach {
            when (it) {
                SystemConstraint.DEVICE_IDLE -> builder.setRequiresDeviceIdle(true)
                SystemConstraint.REQUIRE_BATTERY_NOT_LOW -> builder.setRequiresBatteryNotLow(true)
                SystemConstraint.ALLOW_LOW_BATTERY -> builder.setRequiresBatteryNotLow(false)
                SystemConstraint.ALLOW_LOW_STORAGE -> builder.setRequiresStorageNotLow(false)
            }
        }
        
        block?.invoke(builder)
        return builder.build()
    }

    private fun Constraints.toWorkManagerBackoffPolicy(): androidx.work.BackoffPolicy =
        when (this.backoffPolicy) {
            BackoffPolicy.LINEAR -> androidx.work.BackoffPolicy.LINEAR
            BackoffPolicy.EXPONENTIAL -> androidx.work.BackoffPolicy.EXPONENTIAL
        }

    protected open fun getAlarmReceiverClass(): Class<out AlarmReceiver>? = DefaultAlarmReceiver::class.java

    override fun cancel(id: String) {
        workManager.cancelUniqueWork(id)
        AlarmStore.remove(context, id)
        // Also cancel the actual PendingIntent from AlarmManager.
        // AlarmStore.remove() only removes the SharedPreferences metadata — the alarm
        // would still fire without this call.
        cancelAlarmManagerPendingIntent(id)
    }

    /**
     * Cancels the AlarmManager PendingIntent for the given task ID.
     * Uses FLAG_NO_CREATE to look up the existing PendingIntent without creating a new one.
     * Safe to call even if no alarm was ever scheduled for this ID.
     */
    private fun cancelAlarmManagerPendingIntent(id: String) {
        val receiverClass = getAlarmReceiverClass() ?: return
        try {
            val intent = Intent(context, receiverClass).apply {
                putExtra(AlarmReceiver.EXTRA_TASK_ID, id)
            }
            val existingPi = PendingIntent.getBroadcast(
                context, id.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (existingPi != null) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(existingPi)
                existingPi.cancel()
                Logger.d(LogTags.ALARM, "Cancelled AlarmManager PendingIntent for '$id'")
            }
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Failed to cancel AlarmManager PendingIntent for '$id': ${e.message}")
        }
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_KMP_TASK)
    }

    override fun flushPendingProgress() {}

    override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))
    override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)

    override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
        val steps = chain.getSteps()
        if (steps.isEmpty()) return

        val chainId = id ?: java.util.UUID.randomUUID().toString()
        val wmPolicy = when (policy) {
            ExistingPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
        }

        var continuation = workManager.beginUniqueWork(chainId, wmPolicy, steps.first().map { createWorkRequest(it) })
        steps.drop(1).forEach { step ->
            continuation = continuation.then(step.map { createWorkRequest(it) })
        }
        continuation.enqueue()
    }

    @OptIn(AndroidOnly::class)
    private fun createWorkRequest(task: TaskRequest): OneTimeWorkRequest {
        val wmConstraints = buildWorkManagerConstraints(task.constraints ?: Constraints())
        return buildOneTimeWorkRequest(
            java.util.UUID.randomUUID().toString(),
            task.workerClassName,
            task.constraints ?: Constraints(),
            task.inputJson,
            "chain",
            0L,
            wmConstraints
        )
    }

    override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> =
        KmpWorkManagerRuntime.executionHistoryStore?.getRecords(limit) ?: emptyList()

    override suspend fun clearExecutionHistory() {
        KmpWorkManagerRuntime.executionHistoryStore?.clear()
    }
}
