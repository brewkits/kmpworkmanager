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

    init {
        // Clean up any leaked overflow JSON files from previous crashed sessions
        cleanupZombieInputFiles(context)
    }

    companion object {
        const val TAG_KMP_TASK = "kmp-worker-task"
        const val KEY_INPUT_JSON_FILE = "inputJsonFile"
        // Retry ceiling stamped from Constraints.maxRetries and read back by BaseKmpWorker.
        // Absent → treated as -1 (uncapped). WorkManager has no native max-retry API.
        const val KEY_MAX_RETRIES = "maxRetries"
        // Chain identity, stamped per-step by enqueueChain()/createWorkRequest() and read back
        // by BaseKmpWorker to populate TelemetryHook events and ExecutionRecord. Namespaced
        // (kmp_ prefix) rather than a bare key because WorkContinuation.then() merges a
        // prerequisite step's outputData into the next step's inputData via the default
        // OverwritingInputMerger — a user worker whose own outputData happened to use a bare key
        // would silently clobber this stamp for step 2+.
        const val KEY_CHAIN_ID = "kmp_chain_id"
        const val KEY_STEP_INDEX = "kmp_step_index"
        const val KEY_TOTAL_STEPS = "kmp_total_steps"
        // Per-task deadline: Unix epoch ms. BaseKmpWorker checks this before calling doWork().
        const val KEY_DEADLINE_MS = "kmp_deadline_ms"
        // InputMerger: flag indicating the previous step's output should be merged into inputJson.
        const val KEY_MERGE_PREVIOUS_OUTPUT = "kmp_merge_previous_output"
        /**
         * InputMerger transport key. [BaseKmpWorker] writes the worker's
         * `WorkerResult.Success.data` here as a JSON string on success; WorkManager's default
         * `OverwritingInputMerger` then copies it into the next chain step's `inputData`,
         * where [BaseKmpWorker] reads it back if that step set [KEY_MERGE_PREVIOUS_OUTPUT].
         *
         * Namespaced for the same reason as [KEY_CHAIN_ID]: everything a prerequisite emits
         * lands in the successor's input, so a bare key could be clobbered by a user worker.
         */
        const val KEY_STEP_OUTPUT = "kmp_step_output"
        internal const val OVERFLOW_THRESHOLD_BYTES = 8192 // 8 KB
        private const val ZOMBIE_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private val cleanupStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Resets the cleanup flag. Used only for testing.
         */
        internal fun resetCleanupStartedForTesting() {
            cleanupStarted.set(false)
        }

        /**
         * Scans `cacheDir` for overflow input-JSON files (`kmp_input_*.json`) older than
         * [ZOMBIE_FILE_MAX_AGE_MS] and deletes them.
         *
         * Uses CoroutineScope with Dispatchers.IO to reuse system threads.
         */
        fun cleanupZombieInputFiles(context: Context) {
            if (cleanupStarted.getAndSet(true)) return
            
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
        policy: ExistingPolicy,
        tags: Set<String>,
        deadlineMs: Long?
    ): ScheduleResult {
        // Carries the standalone task's tags/deadline down the same path chain steps use,
        // so `enqueue(tags = ...)` and `TaskRequest(tags = ...)` are stamped identically.
        val taskMeta = TaskRequest(
            workerClassName = workerClassName,
            inputJson = inputJson,
            constraints = constraints,
            tags = tags,
            deadlineMs = deadlineMs
        )

        // Exact alarms bypass WorkManager entirely (AlarmManager + AlarmReceiver), so the
        // tag index and the inputData deadline stamp are both unavailable on that path.
        // Warn instead of silently dropping — a caller who tagged a task expects
        // cancelByTag() to reach it.
        if (trigger is TaskTrigger.Exact && (tags.isNotEmpty() || deadlineMs != null)) {
            Logger.w(
                LogTags.SCHEDULER,
                "Task '$id' uses TaskTrigger.Exact: tags and deadlineMs are NOT supported on the " +
                    "exact-alarm path (it uses AlarmManager, not WorkManager). cancelByTag() will not " +
                    "match this task — cancel it by id instead."
            )
        }

        val result = when (trigger) {
            is TaskTrigger.Exact -> scheduleExactAlarm(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Periodic -> schedulePeriodicWork(id, trigger, workerClassName, constraints, inputJson, policy, taskMeta)
            is TaskTrigger.ContentUri -> scheduleContentUriWork(id, trigger, workerClassName, constraints, inputJson, policy, taskMeta)
            is TaskTrigger.Windowed -> {
                val now = System.currentTimeMillis()
                if (trigger.latest < now) {
                    Logger.w(LogTags.SCHEDULER, "Windowed task '$id' rejected: deadline already passed")
                    ScheduleResult.DEADLINE_ALREADY_PASSED
                } else {
                    val delayMs = (trigger.earliest - now).coerceAtLeast(0L)
                    val updatedConstraints = constraints.copy()
                    // A Windowed trigger's `latest` IS a deadline, so honour it as one even when
                    // the caller passed no explicit deadlineMs. WorkManager cannot enforce
                    // `latest` itself; stamping it here lets BaseKmpWorker skip a run the OS
                    // delayed past the window (iOS ChainExecutor already does this for chains).
                    scheduleOneTimeWork(
                        id, TaskTrigger.OneTime(initialDelayMs = delayMs),
                        workerClassName, updatedConstraints, inputJson, policy,
                        taskMeta.copy(deadlineMs = deadlineMs ?: trigger.latest)
                    )
                }
            }
            is TaskTrigger.OneTime -> scheduleOneTimeWork(id, trigger, workerClassName, constraints, inputJson, policy, taskMeta)
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
        policy: ExistingPolicy,
        taskMeta: TaskRequest? = null
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Scheduling one-time task - ID: '$id', Delay: ${trigger.initialDelayMs}ms")

        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE,
            ExistingPolicy.UPDATE -> ExistingWorkPolicy.REPLACE  // UPDATE has no meaning for one-time tasks
        }

        val wmConstraints = buildWorkManagerConstraints(constraints)
        val workRequest = try {
            Logger.d(LogTags.SCHEDULER, "Building request for $workerClassName")
            buildOneTimeWorkRequest(
                id, workerClassName, constraints, inputJson,
                "one-time", trigger.initialDelayMs, wmConstraints,
                task = taskMeta
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
        policy: ExistingPolicy,
        taskMeta: TaskRequest? = null
    ): ScheduleResult {
        val intervalMs = trigger.intervalMs
        // WorkManager requires flexMs >= 5 min. Default to half the interval when not specified.
        // Clamp between the OS minimum and the interval (flex > interval is nonsensical).
        // If runImmediately is true, we use the full interval as flexMs to allow immediate execution.
        val effectiveFlexMs = (trigger.flexMs ?: if (trigger.runImmediately) intervalMs else (intervalMs / 2))
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
            // UPDATE: preserve the existing schedule interval — only constraints/input change.
            // Requires WorkManager 2.8+. Safe to use: minSdk for this lib is API 26 (WM 2.7 era)
            // but WM 2.8 is pulled transitively via androidx.work:work-runtime:2.8+.
            ExistingPolicy.UPDATE -> androidx.work.ExistingPeriodicWorkPolicy.UPDATE
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

        // Same "user-" namespacing as one-time work so cancelByTag() resolves periodic tasks too.
        taskMeta?.tags?.forEach { builder.addTag("user-$it") }

        // NOTE: deadlineMs is deliberately NOT stamped for periodic work. A deadline is a
        // one-shot concept ("skip if it hasn't started by T"); applied to a recurring task it
        // would permanently disable every future run once T passes, which is a cancel, not a
        // deadline. Callers wanting that should cancel the task instead.
        if (taskMeta?.deadlineMs != null) {
            Logger.w(
                LogTags.SCHEDULER,
                "Periodic task '$id' declared deadlineMs — ignored. A deadline would silently kill " +
                    "every run after it elapses; cancel the task instead."
            )
        }

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
        policy: ExistingPolicy,
        taskMeta: TaskRequest? = null
    ): ScheduleResult {
        val workManagerPolicy = when (policy) {
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingPolicy.REPLACE,
            ExistingPolicy.UPDATE -> ExistingWorkPolicy.REPLACE
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
                "content-uri", 0L, wmConstraints,
                task = taskMeta
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
                        // Register so cancel(id) can find and delete this file. v2.5 QA fix —
                        // previously the file was orphaned in cacheDir until the 24 h sweep ran.
                        OverflowFileRegistry.register(context, id, tempFile.absolutePath)
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
            context, taskIdToRequestCode(id), intent,
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
        wmConstraints: androidx.work.Constraints,
        chainId: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null,
        task: TaskRequest? = null
    ): OneTimeWorkRequest {
        val workData = buildWorkData(
            id, workerClassName, inputJson, constraints.maxRetries,
            chainId, stepIndex, totalSteps,
            deadlineMs = task?.deadlineMs,
            mergeOutputFromPreviousStep = task?.mergeOutputFromPreviousStep ?: false
        )
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

        // Stamp user-defined tags from TaskRequest so cancelByTag() can resolve them.
        // Tag format: "user-<tag>" to avoid collisions with our internal "worker-", "id-", etc.
        task?.tags?.forEach { builder.addTag("user-$it") }

        if (shouldExpedite(task, constraints, initialDelayMs)) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        return builder.build()
    }

    /**
     * `TaskPriority.kt`'s KDoc documents `CRITICAL`/`HIGH` -> `setExpedited()`, `NORMAL`/`LOW` ->
     * standard work. This used to expedite unconditionally (ignoring `task?.priority` entirely),
     * so even LOW-priority chain steps jumped the WorkManager quota queue. Standalone `enqueue()`
     * tasks have no priority parameter on either platform (`TaskPriority` lives on `TaskRequest`,
     * which is chain-step-only by contract) — `task` is null there, so they never qualify. This
     * is an intentional behavior change; see CHANGELOG.
     *
     * `internal` (not `private`) so a Robolectric test can exercise this decision directly
     * without needing to inspect WorkManager's internal `WorkSpec.expedited` field, which has
     * no stable public accessor.
     */
    internal fun shouldExpedite(task: TaskRequest?, constraints: Constraints, initialDelayMs: Long): Boolean {
        val isHighPriority = task?.priority == TaskPriority.HIGH || task?.priority == TaskPriority.CRITICAL
        if (initialDelayMs != 0L || constraints.isHeavyTask || !isHighPriority) return false
        // Expedited work does not support some constraints like charging.
        // Safe to set only if it's a simple urgent task.
        return !constraints.requiresCharging && !constraints.requiresUnmeteredNetwork
    }

    private fun buildWorkData(
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        maxRetries: Int,
        chainId: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null,
        deadlineMs: Long? = null,
        mergeOutputFromPreviousStep: Boolean = false
    ): Data {
        val builder = Data.Builder().putString("workerClassName", workerClassName)
        // Stamp the retry ceiling before any early return so null-input tasks are capped too.
        if (maxRetries >= 0) builder.putInt(KEY_MAX_RETRIES, maxRetries)
        if (chainId != null) builder.putString(KEY_CHAIN_ID, chainId)
        if (stepIndex != null) builder.putInt(KEY_STEP_INDEX, stepIndex)
        if (totalSteps != null) builder.putInt(KEY_TOTAL_STEPS, totalSteps)
        // Stamp deadline so BaseKmpWorker can skip the task if it runs too late.
        if (deadlineMs != null) builder.putLong(KEY_DEADLINE_MS, deadlineMs)
        // Stamp InputMerger flag so BaseKmpWorker merges the previous step's output.
        if (mergeOutputFromPreviousStep) builder.putBoolean(KEY_MERGE_PREVIOUS_OUTPUT, true)
        if (inputJson == null) return builder.build()

        val bytes = inputJson.encodeToByteArray()
        // WorkManager data limit is 10KB. We set threshold to 8KB to be safe.
        return if (bytes.size <= OVERFLOW_THRESHOLD_BYTES) {
            builder.putString("inputJson", inputJson).build()
        } else {
            val tempFile = java.io.File(context.cacheDir, "kmp_input_${java.util.UUID.randomUUID()}.json")
            try {
                tempFile.bufferedWriter().use { it.write(inputJson) }
                // Register so cancel(taskId) can find and delete this file. v2.5 QA fix —
                // previously the file leaked until the 24 h sweep ran.
                OverflowFileRegistry.register(context, taskId, tempFile.absolutePath)
                builder.putString(KEY_INPUT_JSON_FILE, tempFile.absolutePath).build()
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Failed to spill overflow JSON to cacheDir", e)
                throw IllegalArgumentException("JSON too large and failed to spill to disk", e)
            }
        }
    }

    private fun buildPeriodicWorkData(workerClassName: String, inputJson: String?): Data {
        // maxRetries is intentionally NOT stamped for periodic work. A periodic task runs
        // indefinitely by design, so "N+1 total runs" is meaningless; and WorkManager's
        // runAttemptCount for periodic work is version-dependent (may accumulate across periods),
        // which would silently disable retries forever once the cap is hit. maxRetries applies to
        // one-time and chained tasks only — see Constraints.maxRetries.
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
        // Free the overflow `cacheDir/kmp_input_*.json` file (if any) that was associated
        // with this task. Pre-fix the file lingered until the 24 h sweep ran;
        // schedule-then-cancel-heavy workloads (e.g. user spamming draft saves) leaked
        // megabytes into cacheDir. v2.5 QA fix.
        OverflowFileRegistry.consumeAndDelete(context, id)
    }

    /**
     * Cancels all pending or running tasks that carry the given user-defined tag.
     *
     * Tags are stamped as `"user-<tag>"` in WorkManager so they don't collide with
     * the library's own internal tags (`"worker-*"`, `"id-*"`, `"type-*"`).
     */
    override fun cancelByTag(tag: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling tasks by tag '$tag'")
        workManager.cancelAllWorkByTag("user-$tag")
    }

    /**
     * Cancels all pending or running tasks whose workerClassName matches [workerClassName].
     *
     * Uses the `"worker-<className>"` tag that is stamped on every WorkRequest by
     * [buildOneTimeWorkRequest] and [schedulePeriodicWork].
     */
    override fun cancelByWorkerClass(workerClassName: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling tasks by worker class '$workerClassName'")
        workManager.cancelAllWorkByTag("worker-$workerClassName")
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
                context, taskIdToRequestCode(id), intent,
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
        val totalSteps = steps.size
        val wmPolicy = when (policy) {
            ExistingPolicy.REPLACE,
            ExistingPolicy.UPDATE -> ExistingWorkPolicy.REPLACE
            ExistingPolicy.KEEP -> ExistingWorkPolicy.KEEP
        }

        // Stamp chainId/stepIndex/totalSteps into every step's inputData so BaseKmpWorker can
        // recover chain identity for TelemetryHook events and ExecutionRecord — WorkManager's
        // native then()-chaining carries no chain metadata of its own (only a per-step tag).
        var continuation = workManager.beginUniqueWork(
            chainId, wmPolicy, steps.first().map { createWorkRequest(it, chainId, 0, totalSteps) }
        )
        steps.drop(1).forEachIndexed { offset, step ->
            val stepIndex = offset + 1
            continuation = continuation.then(step.map { createWorkRequest(it, chainId, stepIndex, totalSteps) })
        }
        continuation.enqueue()
    }

    @OptIn(AndroidOnly::class)
    private fun createWorkRequest(
        task: TaskRequest,
        chainId: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null
    ): OneTimeWorkRequest {
        val wmConstraints = buildWorkManagerConstraints(task.constraints ?: Constraints())
        return buildOneTimeWorkRequest(
            java.util.UUID.randomUUID().toString(),
            task.workerClassName,
            task.constraints ?: Constraints(),
            task.inputJson,
            "chain",
            0L,
            wmConstraints,
            chainId,
            stepIndex,
            totalSteps,
            task = task  // Pass full TaskRequest to stamp tags, deadlineMs, mergeOutputFromPreviousStep
        )
    }

    override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> =
        KmpWorkManagerRuntime.executionHistoryStore?.getRecords(limit) ?: emptyList()

    override suspend fun clearExecutionHistory() {
        KmpWorkManagerRuntime.executionHistoryStore?.clear()
    }

    private fun taskIdToRequestCode(id: String): Int = PendingIntentCodes.forTaskId(id)
}
