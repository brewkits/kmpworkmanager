@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class) // iOS scheduler exhaustively handles ContentUri by rejecting it — opt-in is explicit and intentional

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.*
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
 * - BGProcessingTask for heavy tasks (minutes)
 * - File-based storage for improved performance and thread safety
 * - Automatic migration from NSUserDefaults (v2.x)
 * - ExistingPolicy support (KEEP/REPLACE)
 * - Task ID validation against Info.plist
 * - Proper error handling with NSError
 *
 * **ChainExecutor Usage:**
 *
 * When registering BGTask handlers in Swift/Objective-C, specify the correct BGTaskType:
 *
 * ```swift
 * // For BGAppRefreshTask (30s limit)
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "app.refresh") { task in
 *     let executor = ChainExecutor(
 *         workerFactory: factory,
 *         taskType: BGTaskType.appRefresh  // ← 20s task timeout, 50s chain timeout
 *     )
 *     // ... execute chains ...
 * }
 *
 * // For BGProcessingTask (5-10 min limit)
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "chain.processor") { task in
 *     let executor = ChainExecutor(
 *         workerFactory: factory,
 *         taskType: BGTaskType.processing  // ← 120s task timeout, 300s chain timeout
 *     )
 *     // ... execute chains ...
 * }
 * ```
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
public class NativeTaskScheduler(
    /**
     * Additional permitted task IDs beyond those in Info.plist.
     */
    additionalPermittedTaskIds: Set<String> = emptySet(),
    /**
     * Minimum free disk space (bytes) required before any task data is written.
     */
    diskSpaceBufferBytes: Long = 50_000_000L,
    /**
     * Optional executor for single tasks — used for simulator fallback.
     */
    private val singleTaskExecutor: SingleTaskExecutor? = null,
    /**
     * Optional executor for task chains — used for simulator fallback.
     */
    private val chainExecutor: ChainExecutor? = null,
    /**
     * Custom storage implementation for testing and isolation.
     */
    internal val fileStorage: IosFileStorage = IosFileStorage(config = IosFileStorageConfig(diskSpaceBufferBytes = diskSpaceBufferBytes)),
    /**
     * Optional scope for background operations (primarily for testing).
     */
    private val scope: CoroutineScope? = null
) : BackgroundTaskScheduler {

    private companion object {
        const val CHAIN_EXECUTOR_IDENTIFIER = "kmp_chain_executor_task"
        const val MASTER_DISPATCHER_IDENTIFIER = "kmp_master_dispatcher_task"
        const val APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS = 978307200.0

        /**
         * Detect if running on iOS Simulator.
         * BGTaskScheduler.submit() always fails with error 1 on simulators.
         */
        private val isSimulator: Boolean by lazy {
            val kmpEnv = platform.posix.getenv("KMP_IS_SIMULATOR")?.toKString()
            val simEnv = platform.posix.getenv("SIMULATOR_DEVICE_NAME")?.toKString()
            kmpEnv == "1" || simEnv != null
        }
    }

    private val migration = StorageMigration(fileStorage = fileStorage)

    /**
     * Background scope for IO operations (migration, file access)
     * Uses Dispatchers.Default to avoid blocking Main thread during initialization
     */
    private val backgroundScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Signal that completes when migration is done
     * Operations should await this before accessing storage
     */
    private val migrationComplete = CompletableDeferred<Unit>()

    /**
     * Task IDs read from Info.plist BGTaskSchedulerPermittedIdentifiers
     */
    private val infoPlistTaskIds: Set<String> = InfoPlistReader.readPermittedTaskIds()

    /**
     * Combined set of permitted task IDs (Info.plist + additional)
     * IMPORTANT: Tasks with IDs not in this list will be silently rejected by iOS
     */
    private val permittedTaskIds: Set<String> = infoPlistTaskIds + additionalPermittedTaskIds

    /**
     * Check if the dedicated master dispatcher task ID is registered in Info.plist.
     */
    private val isMasterDispatcherAvailable: Boolean by lazy {
        NSBundle.mainBundle.bundleIdentifier == null || MASTER_DISPATCHER_IDENTIFIER in permittedTaskIds
    }

    init {
        // Perform one-time migration from NSUserDefaults to file storage
        // Uses background thread to avoid blocking Main thread during app startup
        backgroundScope.launch {
            try {
                // 1. Check if migration is needed
                if (!migration.isMigrated()) {
                    val result = migration.migrate()
                    if (result.success) {
                        Logger.i(LogTags.SCHEDULER, "Storage migration successful: ${result.message}")
                        // Clear old storage immediately after successful migration
                        // to free up space in NSUserDefaults and prevent stale data carry-over.
                        migration.clearOldStorage()
                    } else {
                        Logger.e(LogTags.SCHEDULER, "Storage migration failed: ${result.message}")
                    }
                }

                // 2. Perform periodic maintenance (cleanup stale files, orphaned definitions)
                // Periodic maintenance is triggered by IosFileStorage init block.
                fileStorage.performMaintenanceTasks()
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Storage initialization error", e)
            } finally {
                // Always complete signal, even if migration/maintenance fails
                migrationComplete.complete(Unit)
            }
        }

        // Log permitted task IDs for debugging (lightweight, can stay on caller thread)
        Logger.i(LogTags.SCHEDULER, """
            iOS Task ID Configuration:
            - From Info.plist: ${infoPlistTaskIds.joinToString()}
            - Additional: ${additionalPermittedTaskIds.joinToString()}
            - Total permitted: ${permittedTaskIds.size}
        """.trimIndent())

        // Validate that the chain executor task ID is declared in Info.plist.
        // This is a HARD requirement: if missing, enqueueChain() silently does nothing —
        // iOS rejects the BGProcessingTaskRequest without error at the call site.
        // Log the error here at init time so developers catch it during first run.
        if (NSBundle.mainBundle.bundleIdentifier != null && CHAIN_EXECUTOR_IDENTIFIER !in permittedTaskIds) {
            Logger.e(LogTags.SCHEDULER, """
                ⚠️ CHAIN EXECUTOR TASK ID NOT CONFIGURED — enqueueChain() will fail silently!

                '$CHAIN_EXECUTOR_IDENTIFIER' is missing from Info.plist > BGTaskSchedulerPermittedIdentifiers.

                Fix:
                1. Add to Info.plist:
                   <key>BGTaskSchedulerPermittedIdentifiers</key>
                   <array>
                       <string>$CHAIN_EXECUTOR_IDENTIFIER</string>
                   </array>

                2. Register handler in AppDelegate/iOSApp.swift:
                   BGTaskScheduler.shared.register(forTaskWithIdentifier: "$CHAIN_EXECUTOR_IDENTIFIER") { task in
                       let executor = ChainExecutor(workerFactory: factory)
                       task.expirationHandler = { executor.requestShutdownSync() }
                       Task {
                           await executor.executeChainsInBatch()
                           task.setTaskCompleted(success: true)
                       }
                   }
            """.trimIndent())
        }
    }

    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        // Wait for migration to complete before accessing storage
        migrationComplete.await()

        Logger.i(LogTags.SCHEDULER, "Enqueue request - ID: '$id', Trigger: ${trigger::class.simpleName}, Policy: $policy")

        // Validate task ID against Info.plist permitted identifiers
        if (!validateTaskId(id)) {
            Logger.e(LogTags.SCHEDULER, "Task ID '$id' not in Info.plist BGTaskSchedulerPermittedIdentifiers")
            return ScheduleResult.REJECTED_OS_POLICY
        }

        @Suppress("DEPRECATION")  // Keep backward compatibility for deprecated triggers 
        return when (trigger) {
            is TaskTrigger.Periodic -> schedulePeriodicTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.OneTime -> scheduleOneTimeTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.Exact -> scheduleExactAlarm(id, trigger, workerClassName, constraints, inputJson)
            is TaskTrigger.Windowed -> scheduleWindowedTask(id, trigger, workerClassName, constraints, inputJson, policy)
            is TaskTrigger.ContentUri -> rejectUnsupportedTrigger("ContentUri")
        }
    }

    /**
     * Validate task ID against permitted identifiers in Info.plist.
     * Returns true if the ID is explicitly permitted OR if the Master Dispatcher is available
     * to handle it as a dynamic task.
     */
    private fun validateTaskId(id: String): Boolean {
        // In test environment (no app bundle), skip validation - no Info.plist available
        if (NSBundle.mainBundle.bundleIdentifier == null) return true

        // 1. Check if ID is explicitly registered
        if (id in permittedTaskIds) return true

        // 2. If not registered, check if Master Dispatcher is available to handle it
        if (isMasterDispatcherAvailable) {
            Logger.d(LogTags.SCHEDULER, "Task ID '$id' not in Info.plist. Will handle via Master Dispatcher.")
            return true
        }

        Logger.e(LogTags.SCHEDULER, """
            ❌ Task ID '$id' validation failed

            This ID is not in Info.plist > BGTaskSchedulerPermittedIdentifiers, 
            and the Master Dispatcher ('$MASTER_DISPATCHER_IDENTIFIER') is also missing.

            To fix, add either '$id' or '$MASTER_DISPATCHER_IDENTIFIER' to your Info.plist.
        """.trimIndent())
        return false
    }

    /**
     * Schedule a periodic task with drift-corrected re-scheduling.
     *
     * **Drift correction:**
     * Without correction: each reschedule uses `now + interval`, so iOS delays accumulate
     * (a task delayed 2h produces the next task 2h later than intended, compounding over time).
     *
     * With correction: `anchoredStartMs` is saved on the FIRST schedule and preserved on every
     * reschedule. The next fire time is always the nearest future multiple of `intervalMs` from
     * the anchor — so iOS-caused delays do NOT compound.
     *
     * Example: anchor=T0, interval=1h, iOS delayed execution until T0+2.5h
     * - Without correction: reschedule at T0+2.5h+1h = T0+3.5h (drift accumulates)
     * - With correction:     reschedule at T0+3h (next 1h multiple after T0+2.5h) ✓
     */
    private suspend fun schedulePeriodicTask(
        id: String,
        trigger: TaskTrigger.Periodic,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        Logger.i(LogTags.SCHEDULER, "Scheduling periodic task - ID: '$id', Interval: ${trigger.intervalMs}ms")

        // Read anchor time BEFORE handleExistingPolicy, which may delete metadata (REPLACE).
        // If metadata already has an anchor (reschedule case), preserve it so drift correction
        // remains anchored to the original schedule, not the reschedule time.
        val existingMeta = fileStorage.loadTaskMetadata(id, periodic = true)
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val anchoredStartMs = existingMeta?.get("anchoredStartMs")?.toLongOrNull()
            ?: nowMs  // First-time scheduling: anchor to now

        // Handle ExistingPolicy
        if (!handleExistingPolicy(id, policy, isPeriodicMetadata = true)) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' already exists, KEEP policy - skipping")
            return ScheduleResult.ACCEPTED
        }

        // Compute drift-corrected delay:
        // Find the next multiple of intervalMs from the anchor that is strictly in the future.
        val intervalMs = trigger.intervalMs
        val elapsedMs = nowMs - anchoredStartMs
        val nextN = if (elapsedMs >= 0) elapsedMs / intervalMs + 1 else 1L
        val nextFireMs = anchoredStartMs + nextN * intervalMs
        val delayMs = maxOf(nextFireMs - nowMs, 60_000L) // minimum 1 minute gap

        val driftSavedMs = (intervalMs - delayMs).coerceAtLeast(0)
        if (driftSavedMs > 0) {
            Logger.d(LogTags.SCHEDULER, "Drift correction for '$id': next fire in ${delayMs / 1000}s " +
                "(saved ${driftSavedMs / 1000}s drift vs naive now+interval)")
        }

        // Save metadata for re-scheduling after execution; preserve anchoredStartMs
        val periodicMetadata = mapOf(
            "isPeriodic" to "true",
            "intervalMs" to "$intervalMs",
            "anchoredStartMs" to "$anchoredStartMs",
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "requiresNetwork" to "${constraints.requiresNetwork}",
            "requiresCharging" to "${constraints.requiresCharging}",
            "isHeavyTask" to "${constraints.isHeavyTask}"
        )
        fileStorage.saveTaskMetadata(id, periodicMetadata, periodic = true)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(delayMs / 1000.0)

        return submitTaskRequest(request, "periodic task '$id'")
    }

    /**
     * Schedule a one-time task
     */
    private suspend fun scheduleOneTimeTask(
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
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: "")
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(trigger.initialDelayMs / 1000.0)

        return submitTaskRequest(request, "one-time task '$id'")
    }

    /**
     * Schedule a windowed task (execute within a time window).
     *
     * **iOS Limitation**: iOS's BGTaskScheduler only supports `earliestBeginDate`.
     * There is no "latest" deadline - the system decides when to run the task
     * opportunistically based on device conditions.
     *
     * **Implementation**:
     * - `earliest` → Maps to `earliestBeginDate`
     * - `latest` → Logged as a warning, but not enforced by iOS
     *
     * **Best Practice**: Design your app logic to not depend on the task
     * running before the `latest` time. Use exact alarms if strict timing is required.
     *
     * @param id Unique task identifier
     * @param trigger Windowed trigger with earliest and latest times
     * @param workerClassName Worker class name
     * @param constraints Execution constraints
     * @param inputJson Worker input data
     * @param policy Policy for handling existing tasks
     */
    private suspend fun scheduleWindowedTask(
        id: String,
        trigger: TaskTrigger.Windowed,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val earliestDate = NSDate.dateWithTimeIntervalSince1970(trigger.earliest / 1000.0)
        val latestDate = NSDate.dateWithTimeIntervalSince1970(trigger.latest / 1000.0)

        Logger.i(
            LogTags.SCHEDULER,
            "Scheduling windowed task - ID: '$id', Window: $earliestDate to $latestDate"
        )

        // Deadline guard: reject immediately if the window has already closed.
        if (trigger.latest < nowMs) {
            Logger.e(
                LogTags.SCHEDULER,
                "❌ Windowed task '$id' DEADLINE_ALREADY_PASSED — latest=$latestDate is in the past. " +
                    "The business window is gone; the task will not be scheduled."
            )
            return ScheduleResult.DEADLINE_ALREADY_PASSED
        }

        // QoS-aware warning: BGTaskScheduler is opportunistic — it ignores 'latest'.
        // Warn loudly when the window is tight so developers can switch to Exact trigger.
        val windowMs = trigger.latest - maxOf(trigger.earliest, nowMs)
        val windowMinutes = windowMs / 60_000
        if (windowMinutes < 30) {
            Logger.w(
                LogTags.SCHEDULER,
                "⚠️ Windowed task '$id' has a tight window (~${windowMinutes}min). " +
                    "iOS BGTaskScheduler cannot honour the 'latest' deadline — the task may run AFTER ${latestDate}. " +
                    "If this is deadline-critical (e.g. pre-flight sync), use TaskTrigger.Exact with ExactAlarmIOSBehavior instead."
            )
        } else {
            Logger.w(
                LogTags.SCHEDULER,
                "⚠️ iOS BGTaskScheduler does not enforce 'latest' (${latestDate}). " +
                    "Task may run after the window closes. Use TaskTrigger.Exact for strict deadlines."
            )
        }

        // Handle ExistingPolicy
        if (!handleExistingPolicy(id, policy, isPeriodicMetadata = false)) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' already exists, KEEP policy - skipping")
            return ScheduleResult.ACCEPTED
        }

        val taskMetadata = mapOf(
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "windowEarliest" to trigger.earliest.toString(),
            "windowLatest" to trigger.latest.toString()
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        val request = createBackgroundTaskRequest(id, constraints)
        request.earliestBeginDate = earliestDate

        return submitTaskRequest(request, "windowed task '$id'")
    }

    /**
     * Handle ExistingPolicy - returns true if should proceed with scheduling, false if should skip.
     *
     * **Race condition note (KEEP path):**
     * `isTaskPending()` calls `getPendingTaskRequestsWithCompletionHandler` (async) and
     * `submitTaskRequest` follows separately — these are NOT a single atomic OS operation.
     * In theory, another caller could submit a task between the check and the submit.
     * In practice this window is nanoseconds wide, and iOS BGTaskScheduler semantics handle
     * it gracefully: submitting a request for an already-pending ID silently replaces it.
     * For KEEP, the worst case is an unintended replacement, which self-heals on the next
     * BGTask fire. No data loss or crash results.
     *
     * **REPLACE path:**
     * `cancel(id)` + subsequent `submitTaskRequest` is the closest to atomic the iOS API
     * allows. `cancel()` removes the pending request synchronously (within the same run-loop
     * pass), so the window between cancel and submit is negligible.
     */
    private suspend fun handleExistingPolicy(id: String, policy: ExistingPolicy, isPeriodicMetadata: Boolean): Boolean {
        val existingMetadata = fileStorage.loadTaskMetadata(id, periodic = isPeriodicMetadata)

        if (existingMetadata != null) {
            Logger.d(LogTags.SCHEDULER, "Task '$id' metadata exists, policy: $policy")

            when (policy) {
                ExistingPolicy.KEEP -> {
                    // This prevents issues with stale metadata after crashes
                    val isPending = isTaskPending(id)

                    if (isPending) {
                        Logger.i(LogTags.SCHEDULER, "Task '$id' is pending in BGTaskScheduler, keeping existing task")
                        return false
                    } else {
                        Logger.w(LogTags.SCHEDULER, "Task '$id' metadata exists but not pending (stale). Cleaning up and rescheduling.")
                        fileStorage.deleteTaskMetadata(id, periodic = isPeriodicMetadata)
                        return true
                    }
                }
                ExistingPolicy.REPLACE -> {
                    Logger.i(LogTags.SCHEDULER, "Replacing existing task '$id'")
                    // cancel() calls BGTaskScheduler.cancelTaskRequestWithIdentifier synchronously —
                    // this is the idempotent pre-cancel that makes REPLACE as atomic as iOS allows.
                    // Note: cancel() also deletes metadata; callers reading metadata for reschedule
                    // (e.g. schedulePeriodicTask) must read metadata BEFORE calling this function.
                    cancel(id)
                    return true
                }
            }
        }
        return true
    }

    /**
     * Check if a task is actually pending in BGTaskScheduler.
     */
    private suspend fun isTaskPending(taskId: String): Boolean = suspendCancellableCoroutine { continuation ->
        BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { requests ->
            if (continuation.isActive) {
                val taskList = requests?.filterIsInstance<BGTaskRequest>() ?: emptyList()
                val isPending = taskList.any { it.identifier == taskId }
                continuation.resume(isPending)
            } else {
                Logger.d(LogTags.SCHEDULER, "isTaskPending cancelled for $taskId - callback ignored")
            }
        }

        continuation.invokeOnCancellation {
            Logger.d(LogTags.SCHEDULER, "isTaskPending cancelled for $taskId")
        }
    }

    /**
     * Create appropriate background task request based on constraints
     * Note: iOS BGTaskScheduler does not have a direct QoS API. QoS is managed by iOS based on:
     * - Task type (BGAppRefreshTask vs BGProcessingTask)
     * - System conditions (battery, network, etc.)
     * - App priority and background refresh settings
     */
    private fun createBackgroundTaskRequest(id: String, constraints: Constraints): BGTaskRequest {
        // Log QoS level for developer awareness (iOS manages actual priority automatically)
        Logger.d(LogTags.SCHEDULER, "Task QoS level: ${constraints.qos} (iOS manages priority automatically)")

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
     * Submit task request to BGTaskScheduler with proper error handling.
     * Includes a fallback for iOS Simulator where BGTaskScheduler is unavailable.
     */
    private suspend fun submitTaskRequest(request: BGTaskRequest, taskDescription: String): ScheduleResult {
        val id = request.identifier
        
        // Handle Dynamic Tasks: If the ID is not in Info.plist, we use the internal queue + Master Dispatcher
        // Note: we check this BEFORE the bundleIdentifier == null check to allow integration testing
        // of the dispatcher logic in unit tests.
        if (id != MASTER_DISPATCHER_IDENTIFIER && id != CHAIN_EXECUTOR_IDENTIFIER && id !in infoPlistTaskIds) {
            Logger.i(LogTags.SCHEDULER, "Task '$id' is dynamic (not in Info.plist). Enqueuing for Master Dispatcher.")
            
            try {
                fileStorage.enqueueTask(id)
                
                // Now schedule the Master Dispatcher to process the queue
                val masterRequest = BGProcessingTaskRequest(MASTER_DISPATCHER_IDENTIFIER).apply {
                    requiresNetworkConnectivity = false // Individual task constraints handled by dispatcher
                    requiresExternalPower = false
                    // Use the earliest date from the dynamic task to trigger the dispatcher
                    earliestBeginDate = request.earliestBeginDate
                }
                
                return submitTaskRequest(masterRequest, "Master Dispatcher for task '$id'")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Failed to enqueue dynamic task '$id': ${e.message}")
                return ScheduleResult.REJECTED_OS_POLICY
            }
        }

        // In test environment (no app bundle), BGTaskScheduler is unavailable - simulate success
        if (NSBundle.mainBundle.bundleIdentifier == null) {
            Logger.d(LogTags.SCHEDULER, "Test mode: simulating accepted submission for $taskDescription")
            return ScheduleResult.ACCEPTED
        }

        // iOS Simulator Fallback
        if (isSimulator) {
            Logger.w(LogTags.SCHEDULER, "iOS Simulator detected — BGTaskScheduler is unavailable.")
            
            if (singleTaskExecutor != null) {
                val delaySeconds = request.earliestBeginDate?.timeIntervalSinceNow ?: 0.0
                val delayMs = (delaySeconds * 1000.0).toLong().coerceAtLeast(0L)
                
                Logger.i(LogTags.SCHEDULER, "Simulator fallback: executing '$taskDescription' in ${delayMs}ms")
                
                backgroundScope.launch {
                    delay(delayMs)
                    val taskId = request.identifier
                    val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, fileStorage)
                    if (meta != null) {
                        singleTaskExecutor.executeTask(meta.workerClassName, meta.inputJson)
                        if (meta.isPeriodic && meta.rawMeta != null) {
                            IosBackgroundTaskHandler.reschedulePeriodicTask(
                                taskId, meta.workerClassName, meta.inputJson, meta.rawMeta, this@NativeTaskScheduler
                            )
                        }
                    }
                }
                return ScheduleResult.ACCEPTED
            } else {
                Logger.e(LogTags.SCHEDULER, "Simulator fallback failed: singleTaskExecutor not provided to NativeTaskScheduler")
                // Fall through to OS call which will fail with error 1, providing standard diagnostic
            }
        }

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
     * Schedule exact alarm with iOS-specific behavior handling.
     *
     * Implements ExactAlarmIOSBehavior for transparent exact alarm handling.
     *
     * **Background**: iOS does NOT support background code execution at exact times.
     * This method provides three explicit behaviors based on constraints.exactAlarmIOSBehavior:
     * 1. SHOW_NOTIFICATION (default): Display UNNotification at exact time
     * 2. ATTEMPT_BACKGROUND_RUN: Schedule BGAppRefreshTask (not guaranteed to run at exact time)
     * 3. THROW_ERROR: Throw exception to force developer awareness
     *
     * @param id Task identifier
     * @param trigger Exact trigger with timestamp
     * @param workerClassName Worker class (used only for ATTEMPT_BACKGROUND_RUN, ignored for SHOW_NOTIFICATION)
     * @param constraints Execution constraints (contains exactAlarmIOSBehavior)
     * @param inputJson Worker input data
     * @return ScheduleResult indicating success/failure
     * @throws UnsupportedOperationException if exactAlarmIOSBehavior is THROW_ERROR
     */
    private suspend fun scheduleExactAlarm(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?
    ): ScheduleResult {
        val behavior = constraints.exactAlarmIOSBehavior

        Logger.i(
            LogTags.ALARM,
            "Scheduling exact alarm - ID: '$id', Time: ${trigger.atEpochMillis}, Behavior: $behavior"
        )

        return when (behavior) {
            ExactAlarmIOSBehavior.SHOW_NOTIFICATION -> {
                scheduleExactNotification(id, trigger, workerClassName, inputJson)
            }

            ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN -> {
                Logger.w(
                    LogTags.ALARM,
                    "⚠️ ATTEMPT_BACKGROUND_RUN: iOS will TRY to run around specified time, but timing is NOT guaranteed"
                )
                scheduleExactBackgroundTask(id, trigger, workerClassName, constraints, inputJson)
            }

            ExactAlarmIOSBehavior.THROW_ERROR -> {
                val errorMessage = """
                    ❌ iOS does not support exact alarms for background code execution.

                    TaskTrigger.Exact on iOS can only:
                    1. Show notification at exact time (SHOW_NOTIFICATION)
                    2. Attempt opportunistic background run (ATTEMPT_BACKGROUND_RUN - not guaranteed)

                    To fix this error, choose one of:

                    Option 1: Show notification (user-facing events)
                    Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION)

                    Option 2: Best-effort background run (non-critical sync)
                    Constraints(exactAlarmIOSBehavior = ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN)

                    Option 3: Platform-specific implementation
                    if (Platform.isIOS) {
                        // Use notification or rethink approach
                    } else {
                        // Use TaskTrigger.Exact on Android
                    }

                    See: ExactAlarmIOSBehavior documentation
                """.trimIndent()

                Logger.e(LogTags.ALARM, errorMessage)
                throw UnsupportedOperationException(errorMessage)
            }
        }
    }

    /**
     * Schedule exact notification using UNUserNotificationCenter.
     * This is the default and recommended approach for exact alarms on iOS.
     *
     * Metadata is always saved to disk so that [checkAndExecuteMissedExactAlarms]
     * can catch up if notifications are denied or the user never taps the notification.
     *
     * NOTE: The notification body intentionally does NOT include inputJson.
     * Worker input data may contain PII or secrets; exposing it in a system
     * notification would be a privacy/security violation.
     */
    private suspend fun scheduleExactNotification(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        inputJson: String?
    ): ScheduleResult {
        Logger.i(LogTags.ALARM, "Scheduling exact notification - ID: '$id', Time: ${trigger.atEpochMillis}")

        // Save metadata so catch-up can execute this task if the notification is missed
        fileStorage.saveTaskMetadata(id, mapOf(
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "targetTime" to trigger.atEpochMillis.toString(),
            "exactAlarm" to "true"
        ), periodic = false)

        val content = UNMutableNotificationContent().apply {
            setTitle("Scheduled Task")
            setBody("Task '$workerClassName' is ready to run")
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
     * Schedule background task to run around the exact time (best effort).
     *
     * **IMPORTANT**: iOS decides when to actually run the task. May be delayed by
     * minutes to hours, or may not run at all.
     *
     */
    private suspend fun scheduleExactBackgroundTask(
        id: String,
        trigger: TaskTrigger.Exact,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?
    ): ScheduleResult {
        Logger.i(
            LogTags.ALARM,
            "Scheduling best-effort background task - ID: '$id', Target time: ${trigger.atEpochMillis}"
        )

        // Save metadata for task execution (also enables catch-up via checkAndExecuteMissedExactAlarms)
        val taskMetadata = mapOf(
            "workerClassName" to workerClassName,
            "inputJson" to (inputJson ?: ""),
            "targetTime" to trigger.atEpochMillis.toString(),
            "exactAlarm" to "true"
        )
        fileStorage.saveTaskMetadata(id, taskMetadata, periodic = false)

        // Schedule BGAppRefreshTask with earliestBeginDate = exact time
        val request = createBackgroundTaskRequest(id, constraints)

        val unixTimestampInSeconds = trigger.atEpochMillis / 1000.0
        val appleTimestamp = unixTimestampInSeconds - APPLE_TO_UNIX_EPOCH_OFFSET_SECONDS
        val targetDate = NSDate(timeIntervalSinceReferenceDate = appleTimestamp)

        request.earliestBeginDate = targetDate

        Logger.w(
            LogTags.ALARM,
            """
            ⚠️ Best-effort scheduling - iOS will ATTEMPT to run around ${targetDate}
            - Timing is NOT guaranteed (may be delayed significantly)
            - May not run if device is in Low Power Mode
            - May not run if app exceeded background budget
            """.trimIndent()
        )

        return submitTaskRequest(request, "best-effort background task '$id'")
    }

    /**
     * Execute any exact-alarm tasks that were missed while the app was not running.
     *
     * **When to call**: From `applicationDidBecomeActive` in your AppDelegate. This covers two
     * failure modes:
     * 1. **Notification permission denied** — `UNUserNotificationCenter` never fires, so the task
     *    is silently lost without this catch-up.
     * 2. **User ignored/dismissed notification** — the notification fired but no background work
     *    was triggered.
     *
     * Tasks scheduled with [ExactAlarmIOSBehavior.ATTEMPT_BACKGROUND_RUN] are also caught here
     * if iOS never ran the BGTask opportunity.
     *
     * The scan runs on a background thread — [execute] is called from a background thread.
     * If your worker execution needs to happen on the main thread, dispatch inside [execute].
     *
     * ```swift
     * // AppDelegate.swift
     * func applicationDidBecomeActive(_ application: UIApplication) {
     *     KmpWorkManager.shared.backgroundTaskScheduler.checkAndExecuteMissedExactAlarms { workerName, inputJson in
     *         // Called on a background thread — dispatch to main if needed
     *         DispatchQueue.main.async {
     *             let worker = workerFactory.createWorker(workerName)
     *             worker?.doWork(inputJson: inputJson)
     *         }
     *     }
     * }
     * ```
     *
     * @param execute Called for each missed task on a **background thread**. Receives the worker
     *   class name and optional input JSON. Exceptions thrown here are caught and logged; other
     *   tasks continue.
     */
    fun checkAndExecuteMissedExactAlarms(execute: (workerClassName: String, inputJson: String?) -> Unit) {
        // Run on background scope to avoid blocking the main thread (UI jank / ANR risk).
        // runBlocking on Main was previously used here because loadTaskMetadata is suspend.
        // Moving to backgroundScope.launch eliminates the main-thread block while keeping
        // all file operations off the caller's thread.
        backgroundScope.launch {
            val nowMs = NSDate().timeIntervalSince1970 * 1000.0

            val taskIds = fileStorage.listTaskIds()
            if (taskIds.isEmpty()) return@launch

            Logger.d(LogTags.ALARM, "Catch-up scan: checking ${taskIds.size} metadata entries for missed exact alarms")

            taskIds.forEach { taskId ->
                val metadata = fileStorage.loadTaskMetadata(taskId, periodic = false) ?: return@forEach
                if (metadata["exactAlarm"] != "true") return@forEach

                val targetTimeMs = metadata["targetTime"]?.toDoubleOrNull() ?: return@forEach
                if (nowMs < targetTimeMs) return@forEach  // not yet due

                val workerClassName = metadata["workerClassName"] ?: return@forEach
                val inputJson = metadata["inputJson"]?.takeIf { it.isNotEmpty() }

                val overdueSeconds = ((nowMs - targetTimeMs) / 1000.0).toLong()
                Logger.i(LogTags.ALARM, "🔄 Catch-up: executing missed exact alarm '$taskId' " +
                    "(worker=$workerClassName, overdue=${overdueSeconds}s)")

                try {
                    execute(workerClassName, inputJson)
                    // Clean up after successful execution
                    fileStorage.deleteTaskMetadata(taskId, periodic = false)
                    cancel(taskId)  // Remove pending notification if not yet shown
                    Logger.i(LogTags.ALARM, "✅ Catch-up complete for '$taskId'")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Catch-up failed for '$taskId'", e)
                    // Leave metadata intact so the next foreground attempt can retry
                }
            }
        }
    }

    /**
     * Reject unsupported trigger type with logging
     */
    private fun rejectUnsupportedTrigger(triggerName: String): ScheduleResult {
        Logger.w(LogTags.SCHEDULER, "$triggerName triggers not supported on iOS (Android only)")
        return ScheduleResult.REJECTED_OS_POLICY
    }

    override fun beginWith(task: TaskRequest): TaskChain {
        return TaskChain(this, listOf(task))
    }

    override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        return TaskChain(this, tasks)
    }

    /**
     * Enqueues a task chain for execution.
     *
     * Now suspending to prevent deadlock risks.
     * Removed runBlocking calls that could cause deadlocks under load.
     */
    override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
        // Await migration before accessing storage, same as enqueue()
        migrationComplete.await()

        // HARD REQUIREMENT: Ensure the generic chain executor task ID is declared in Info.plist.
        // If missing, iOS rejects the BGProcessingTaskRequest without error at the call site.
        if (NSBundle.mainBundle.bundleIdentifier != null && CHAIN_EXECUTOR_IDENTIFIER !in permittedTaskIds) {
            val errorMsg = "CHAIN EXECUTOR TASK ID '$CHAIN_EXECUTOR_IDENTIFIER' NOT CONFIGURED. " +
                "Add it to Info.plist > BGTaskSchedulerPermittedIdentifiers or chains will never execute."
            Logger.e(LogTags.CHAIN, "❌ $errorMsg")
            throw IllegalStateException(errorMsg)
        }

        val steps = chain.getSteps()
        if (steps.isEmpty()) {
            Logger.w(LogTags.CHAIN, "Attempted to enqueue empty chain, ignoring")
            return
        }

        val chainId = id ?: NSUUID.UUID().UUIDString()
        Logger.i(LogTags.CHAIN, "Enqueuing chain - ID: $chainId, Steps: ${steps.size}, Policy: $policy")

        if (policy == ExistingPolicy.REPLACE) {
            // Cancel any running execution of this chain across ALL ChainExecutor
            // instances (e.g. a prior BGTask still running when the new request arrives).
            ChainJobRegistry.cancel(chainId)
        }

        if (fileStorage.chainExists(chainId)) {
            when (policy) {
                ExistingPolicy.KEEP -> {
                    Logger.i(LogTags.CHAIN, "Chain $chainId already exists, KEEP policy - skipping")
                    return
                }
                ExistingPolicy.REPLACE -> {
                    Logger.i(LogTags.CHAIN, "Chain $chainId exists, REPLACE policy - using atomic replace...")
                    try {
                        fileStorage.replaceChainAtomic(chainId, steps)
                        Logger.i(LogTags.CHAIN, "✅ Chain $chainId replaced atomically")
                        return // Early return - atomic operation already enqueued
                    } catch (e: Exception) {
                        Logger.e(LogTags.CHAIN, "Failed to replace chain $chainId atomically", e)
                        throw e
                    }
                }
            }
        }

        // 1. Save the chain definition
        fileStorage.saveChainDefinition(chainId, steps)

        // 2. Add the chainId to the execution queue
        try {
            fileStorage.enqueueChain(chainId)
            Logger.d(LogTags.CHAIN, "Added chain $chainId to execution queue. Queue size: ${fileStorage.getQueueSize()}")
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "Failed to enqueue chain $chainId", e)
            throw e
        }

        // 3. Schedule the generic chain executor task
        // ChainExecutor should be created with BGTaskType.PROCESSING in the handler.
        // requiresNetworkConnectivity is false: individual workers handle their own
        // network requirements; the chain executor itself does not need connectivity.
        val request = BGProcessingTaskRequest(identifier = CHAIN_EXECUTOR_IDENTIFIER).apply {
            earliestBeginDate = NSDate().dateByAddingTimeInterval(1.0)
            requiresNetworkConnectivity = false
        }

        // iOS Simulator Fallback for Chains
        if (isSimulator) {
            Logger.w(LogTags.CHAIN, "iOS Simulator detected — BGTaskScheduler is unavailable.")
            
            if (chainExecutor != null) {
                Logger.i(LogTags.CHAIN, "Simulator fallback: executing chain batch immediately")
                backgroundScope.launch {
                    delay(1000) // Small delay to mimic earliestBeginDate
                    chainExecutor.resetShutdownState()
                    chainExecutor.executeChainsInBatch(
                        maxChains = 3, 
                        totalTimeoutMs = chainExecutor.chainTimeout
                    )
                }
                return
            } else {
                Logger.e(LogTags.CHAIN, "Simulator fallback failed: chainExecutor not provided to NativeTaskScheduler")
                // Fall through to OS call which will fail with error 1
            }
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

    override fun cancel(id: String) {
        Logger.i(LogTags.SCHEDULER, "Cancelling task/notification with ID '$id'")

        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
        // UNUserNotificationCenter requires a valid app bundle.
        // Without one (e.g. in test .kexe), currentNotificationCenter() crashes with
        // NSInternalInconsistencyException: bundleProxyForCurrentProcess is nil.
        if (NSBundle.mainBundle.bundleIdentifier != null) {
            UNUserNotificationCenter.currentNotificationCenter()
                .removePendingNotificationRequestsWithIdentifiers(listOf(id))
        }

        // Clean up metadata from file storage
        fileStorage.deleteTaskMetadata(id, periodic = false)
        fileStorage.deleteTaskMetadata(id, periodic = true)

        Logger.d(LogTags.SCHEDULER, "Cancelled task '$id' and cleaned up metadata")
    }

    override fun cancelAll() {
        Logger.w(LogTags.SCHEDULER, "Cancelling ALL tasks and notifications")

        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
        // UNUserNotificationCenter requires a valid app bundle (see cancel() above)
        if (NSBundle.mainBundle.bundleIdentifier != null) {
            UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
        }

        // Cleanup file storage (garbage collection)
        fileStorage.cleanupStaleMetadata(olderThanDays = 0) // Clean all metadata immediately

        Logger.d(LogTags.SCHEDULER, "Cancelled all tasks and cleaned up metadata")
    }

    /**
     * Cancel the background scope (call when scheduler is no longer needed).
     * In production, the scheduler is typically a singleton for app lifetime.
     * Call this in tests to prevent coroutine leaks.
     */
    fun close() {
        backgroundScope.cancel()
        Logger.d(LogTags.SCHEDULER, "NativeTaskScheduler background scope cancelled")
    }

    /**
     * Flush all pending progress updates immediately.
     * Implementation for iOS - delegates to IosFileStorage.
     *
     * This method ensures data durability before app suspension.
     * Critical for iOS where apps can be suspended/terminated aggressively.
     */
    override fun flushPendingProgress() {
        Logger.i(LogTags.SCHEDULER, "Flushing pending progress (iOS)")
        fileStorage.flushAllPendingProgress()
    }

    override suspend fun getExecutionHistory(limit: Int): List<dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord> =
        KmpWorkManagerRuntime.executionHistoryStore?.getRecords(limit) ?: emptyList()

    override suspend fun clearExecutionHistory() {
        KmpWorkManagerRuntime.executionHistoryStore?.clear()
    }
}
