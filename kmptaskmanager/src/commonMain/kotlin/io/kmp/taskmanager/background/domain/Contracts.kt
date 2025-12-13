package io.kmp.taskmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * Defines the trigger condition for a background task.
 *
 * This sealed interface provides a type-safe way to specify when and how
 * background tasks should be executed. Each trigger type has different
 * platform support and scheduling characteristics.
 *
 * Platform Support Matrix:
 * - Periodic, OneTime, Exact: ✅ Android ✅ iOS
 * - Windowed: ❌ Not implemented
 * - ContentUri, Battery*, Storage*, DeviceIdle: ✅ Android only
 */
sealed interface TaskTrigger {

    /**
     * Triggers at a precise moment in time using exact alarm.
     *
     * **Use Cases**: Alarms, reminders, time-critical user-facing events
     *
     * **Android Implementation**:
     * - Uses `AlarmManager.setExactAndAllowWhileIdle()`
     * - Requires `SCHEDULE_EXACT_ALARM` permission on Android 12+ (API 31+)
     * - Can wake device from doze mode
     * - Accuracy: ±1 minute window on API 31+
     *
     * **iOS Implementation**:
     * - Uses `UNUserNotificationCenter` for scheduled local notifications
     * - Displays notification at exact time
     * - Does not execute code in background (notification-based)
     *
     * @param atEpochMillis Unix timestamp in milliseconds when alarm should trigger
     *
     * **Example**:
     * ```kotlin
     * val targetTime = Clock.System.now().plus(1.hours).toEpochMilliseconds()
     * TaskTrigger.Exact(atEpochMillis = targetTime)
     * ```
     */
    data class Exact(val atEpochMillis: Long) : TaskTrigger

    /**
     * Triggers within a time window - **NOT IMPLEMENTED**.
     *
     * Allows the OS to optimize execution by choosing best time within window.
     *
     * @param earliest Earliest time to execute (Unix epoch milliseconds)
     * @param latest Latest time to execute (Unix epoch milliseconds)
     */
    data class Windowed(val earliest: Long, val latest: Long) : TaskTrigger

    /**
     * Triggers periodically at regular intervals.
     *
     * **Use Cases**: Data sync, content refresh, periodic maintenance
     *
     * **Android Implementation**:
     * - Uses `WorkManager.PeriodicWorkRequest`
     * - **Minimum interval: 15 minutes (900,000ms)**
     * - Actual execution time is opportunistic (OS decides best time)
     * - Survives app restart and device reboot
     * - `flexMs` creates execution window: [intervalMs - flexMs, intervalMs]
     *
     * **iOS Implementation**:
     * - Uses `BGAppRefreshTask` (light tasks) or `BGProcessingTask` (heavy tasks)
     * - **No minimum interval**, but iOS decides actual execution time
     * - Execution heavily influenced by battery, usage patterns
     * - Must manually re-schedule after each execution
     * - Low Power Mode may defer execution significantly
     *
     * @param intervalMs Repetition interval in milliseconds (Android min: 900,000ms / 15min)
     * @param flexMs Android-only: Flex window in milliseconds for execution optimization.
     *               Task can execute anytime within [intervalMs - flexMs, intervalMs].
     *               Helps battery by batching multiple tasks. iOS ignores this parameter.
     *
     * **Example**:
     * ```kotlin
     * // Sync every 15 minutes with 5-minute flex window
     * TaskTrigger.Periodic(
     *     intervalMs = 900_000,  // 15 minutes
     *     flexMs = 300_000       // 5 minutes flex
     * )
     * ```
     */
    data class Periodic(val intervalMs: Long, val flexMs: Long? = null) : TaskTrigger

    /**
     * Triggers once after an optional initial delay.
     *
     * **Use Cases**: One-time upload, deferred processing, delayed execution
     *
     * **Android Implementation**:
     * - Uses `WorkManager.OneTimeWorkRequest`
     * - Constraints-aware (network, battery, etc.)
     * - Survives app restart and device reboot
     * - Can use ForegroundService for long-running tasks (isHeavyTask = true)
     *
     * **iOS Implementation**:
     * - Uses `BGAppRefreshTask` (≤30s) or `BGProcessingTask` (≤60s)
     * - iOS decides actual execution time (can be delayed)
     * - `earliestBeginDate` = now + initialDelayMs
     * - Execution not guaranteed if app is force-quit by user
     *
     * @param initialDelayMs Delay before execution in milliseconds (default: 0 = immediate)
     *
     * **Example**:
     * ```kotlin
     * // Upload data after 5 seconds
     * TaskTrigger.OneTime(initialDelayMs = 5000)
     * ```
     */
    data class OneTime(val initialDelayMs: Long = 0) : TaskTrigger

    /**
     * Triggers when a content URI changes - **ANDROID ONLY**.
     *
     * **Use Cases**: React to MediaStore changes, Contact updates, file modifications
     *
     * **Android Implementation**:
     * - Uses `WorkManager` with `ContentUriTriggers`
     * - Monitors content provider via `ContentObserver`
     * - Common URIs: `content://media/external/images/media`, `content://contacts`
     *
     * **iOS**: Returns `ScheduleResult.REJECTED_OS_POLICY`
     *
     * @param uriString Content URI to observe (e.g., "content://media/external/images/media")
     * @param triggerForDescendants If true, triggers for changes in descendant URIs as well
     *
     * **Example**:
     * ```kotlin
     * // Monitor new photos added
     * TaskTrigger.ContentUri(
     *     uriString = "content://media/external/images/media",
     *     triggerForDescendants = true
     * )
     * ```
     */
    data class ContentUri(
        val uriString: String,
        val triggerForDescendants: Boolean = false
    ) : TaskTrigger

    /**
     * Triggers when device storage is low - **ANDROID ONLY**.
     *
     * **⚠️ DEPRECATED**: Use `Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))` instead.
     *
     * This incorrectly represented a constraint as a trigger. The new API correctly models this
     * as a constraint that allows tasks to run when storage is low.
     *
     * **Migration**:
     * ```kotlin
     * // Old (v2.x):
     * scheduler.enqueue(id, trigger = TaskTrigger.StorageLow, ...)
     *
     * // New (v3.0.0+):
     * scheduler.enqueue(
     *     id,
     *     trigger = TaskTrigger.OneTime(),
     *     constraints = Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))
     * )
     * ```
     */
    @Deprecated(
        message = "StorageLow is a constraint, not a trigger. Use Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))",
        replaceWith = ReplaceWith("Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE))"),
        level = DeprecationLevel.WARNING
    )
    data object StorageLow : TaskTrigger

    /**
     * Triggers when battery is low - **ANDROID ONLY**.
     *
     * **⚠️ DEPRECATED**: Use `Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))` instead.
     *
     * **Migration**:
     * ```kotlin
     * // Old (v2.x):
     * scheduler.enqueue(id, trigger = TaskTrigger.BatteryLow, ...)
     *
     * // New (v3.0.0+):
     * scheduler.enqueue(
     *     id,
     *     trigger = TaskTrigger.OneTime(),
     *     constraints = Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))
     * )
     * ```
     */
    @Deprecated(
        message = "BatteryLow is a constraint. Use Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))",
        replaceWith = ReplaceWith("Constraints(systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY))"),
        level = DeprecationLevel.WARNING
    )
    data object BatteryLow : TaskTrigger

    /**
     * Triggers when battery is okay/not low - **ANDROID ONLY**.
     *
     * **⚠️ DEPRECATED**: Use `Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))` instead.
     *
     * **Migration**:
     * ```kotlin
     * // Old (v2.x):
     * scheduler.enqueue(id, trigger = TaskTrigger.BatteryOkay, ...)
     *
     * // New (v3.0.0+):
     * scheduler.enqueue(
     *     id,
     *     trigger = TaskTrigger.OneTime(),
     *     constraints = Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))
     * )
     * ```
     */
    @Deprecated(
        message = "BatteryOkay is a constraint. Use Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))",
        replaceWith = ReplaceWith("Constraints(systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))"),
        level = DeprecationLevel.WARNING
    )
    data object BatteryOkay : TaskTrigger

    /**
     * Triggers when device is idle/dozing - **ANDROID ONLY**.
     *
     * **⚠️ DEPRECATED**: Use `Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))` instead.
     *
     * **Migration**:
     * ```kotlin
     * // Old (v2.x):
     * scheduler.enqueue(id, trigger = TaskTrigger.DeviceIdle, ...)
     *
     * // New (v3.0.0+):
     * scheduler.enqueue(
     *     id,
     *     trigger = TaskTrigger.OneTime(),
     *     constraints = Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))
     * )
     * ```
     */
    @Deprecated(
        message = "DeviceIdle is a constraint. Use Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))",
        replaceWith = ReplaceWith("Constraints(systemConstraints = setOf(SystemConstraint.DEVICE_IDLE))"),
        level = DeprecationLevel.WARNING
    )
    data object DeviceIdle : TaskTrigger
}

/**
 * System-level constraints for task execution.
 * These are conditions that must be met for a task to run.
 *
 * **Platform Support**: Android only (iOS ignores these)
 *
 * **v3.0.0+**: Replaces deprecated TaskTrigger variants (BatteryLow, StorageLow, etc.)
 * which incorrectly represented constraints as triggers.
 */
@Serializable
enum class SystemConstraint {
    /**
     * Allow task to run even when storage is low.
     * Android: `setRequiresStorageNotLow(false)`
     */
    ALLOW_LOW_STORAGE,

    /**
     * Allow task to run even when battery is low.
     * Android: `setRequiresBatteryNotLow(false)`
     */
    ALLOW_LOW_BATTERY,

    /**
     * Require battery to be NOT low.
     * Android: `setRequiresBatteryNotLow(true)`
     */
    REQUIRE_BATTERY_NOT_LOW,

    /**
     * Require device to be idle/dozing.
     * Android: `setRequiresDeviceIdle(true)`
     */
    DEVICE_IDLE
}

/**
 * Defines the constraints under which a background task can run.
 *
 * Constraints allow fine-grained control over when tasks execute,
 * helping optimize battery life and network usage.
 *
 * **Platform Support**:
 * - Most constraints work on both platforms
 * - Some are platform-specific (see individual field docs)
 */
@Serializable
data class Constraints(
    /**
     * Requires any type of network connectivity (Wi-Fi, cellular, etc.).
     *
     * **Android**: Uses `NetworkType.CONNECTED` constraint
     * **iOS**: Uses `requiresNetworkConnectivity` on `BGProcessingTask` only
     *          (BGAppRefreshTask doesn't support network constraint)
     *
     * Default: false
     */
    val requiresNetwork: Boolean = false,

    /**
     * Requires unmetered network (typically Wi-Fi) - **ANDROID ONLY**.
     *
     * **Android**: Uses `NetworkType.UNMETERED` constraint
     * **iOS**: Not supported, falls back to `requiresNetwork`
     *
     * **Use Cases**: Large uploads/downloads, video processing
     *
     * Default: false
     */
    val requiresUnmeteredNetwork: Boolean = false,

    /**
     * Requires device to be charging.
     *
     * **Android**: Uses `setRequiresCharging(true)` constraint
     * **iOS**: Uses `requiresExternalPower` on `BGProcessingTask` only
     *          (BGAppRefreshTask doesn't support charging constraint)
     *
     * **Use Cases**: Heavy processing, large syncs
     *
     * Default: false
     */
    val requiresCharging: Boolean = false,

    /**
     * Hint to allow execution during device idle/doze mode - **ANDROID ONLY**.
     *
     * **Android**: Used with AlarmManager's `setExactAndAllowWhileIdle()`
     * **iOS**: Not applicable (iOS decides execution timing)
     *
     * **Note**: This is a HINT, not a guarantee. System may still defer.
     *
     * Default: false
     */
    val allowWhileIdle: Boolean = false,

    /**
     * Quality of Service hint for task priority - **iOS ONLY**.
     *
     * **iOS**: Maps to `DispatchQoS` for task execution priority:
     * - `Utility`: Low priority, user not waiting
     * - `Background`: Default, deferred execution
     * - `UserInitiated`: Important, user may be waiting
     * - `UserInteractive`: Critical, user actively waiting
     *
     * **Android**: Ignored (WorkManager handles priority automatically)
     *
     * Default: Qos.Background
     */
    val qos: Qos = Qos.Background,

    /**
     * Indicates this is a long-running or heavy task requiring special handling.
     *
     * **Android**: Uses ForegroundService with persistent notification
     * - Task can run indefinitely while service is foreground
     * - Prevents system from killing the task
     * - Requires `FOREGROUND_SERVICE` permission
     * - Shows persistent notification to user
     *
     * **iOS**: Uses `BGProcessingTask` (≤60s) instead of `BGAppRefreshTask` (≤30s)
     * - Double the execution time limit
     * - Better for CPU-intensive work
     * - Still limited by iOS (no indefinite execution)
     *
     * **Use Cases**: File upload, video processing, prime calculation
     *
     * Default: false
     */
    val isHeavyTask: Boolean = false,

    /**
     * Backoff policy when task fails and needs retry - **ANDROID ONLY**.
     *
     * **Android**: Determines retry behavior for failed WorkManager tasks
     * - `EXPONENTIAL`: Delay doubles after each retry (30s, 60s, 120s, ...)
     * - `LINEAR`: Constant delay between retries
     *
     * **iOS**: Not applicable (manual retry required)
     *
     * Default: BackoffPolicy.EXPONENTIAL
     */
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,

    /**
     * Initial backoff delay in milliseconds when task fails - **ANDROID ONLY**.
     *
     * **Android**: Starting delay before first retry
     * - Minimum: 10,000ms (10 seconds)
     * - Subsequent retries follow backoffPolicy
     *
     * **iOS**: Not applicable
     *
     * **Example**:
     * ```kotlin
     * Constraints(
     *     backoffPolicy = BackoffPolicy.EXPONENTIAL,
     *     backoffDelayMs = 30_000  // Start with 30s, then 60s, 120s, ...
     * )
     * ```
     *
     * Default: 30,000ms (30 seconds)
     */
    val backoffDelayMs: Long = 30_000,

    /**
     * System-level constraints for task execution - **ANDROID ONLY**.
     *
     * **v3.0.0+**: Replaces deprecated TaskTrigger variants (BatteryLow, StorageLow, etc.)
     *
     * **Android**: Maps to WorkManager constraint methods:
     * - `ALLOW_LOW_STORAGE` → `setRequiresStorageNotLow(false)`
     * - `ALLOW_LOW_BATTERY` → `setRequiresBatteryNotLow(false)`
     * - `REQUIRE_BATTERY_NOT_LOW` → `setRequiresBatteryNotLow(true)`
     * - `DEVICE_IDLE` → `setRequiresDeviceIdle(true)`
     *
     * **iOS**: Ignored (no equivalent constraints)
     *
     * **Example**:
     * ```kotlin
     * Constraints(
     *     systemConstraints = setOf(
     *         SystemConstraint.DEVICE_IDLE,
     *         SystemConstraint.REQUIRE_BATTERY_NOT_LOW
     *     )
     * )
     * ```
     *
     * Default: emptySet()
     */
    val systemConstraints: Set<SystemConstraint> = emptySet()
)

/**
 * Backoff policy for task retry behavior.
 *
 * Used by Android WorkManager to determine retry intervals when tasks fail.
 */
enum class BackoffPolicy {
    /**
     * Linear backoff - constant delay between retries.
     *
     * Retry delays: delay, delay, delay, ...
     *
     * **Example**: If delay = 30s: 30s, 30s, 30s, ...
     */
    LINEAR,

    /**
     * Exponential backoff - delay doubles after each retry.
     *
     * Retry delays: delay, delay*2, delay*4, delay*8, ...
     *
     * **Example**: If delay = 30s: 30s, 60s, 120s, 240s, ...
     */
    EXPONENTIAL
}

/**
 * Quality of Service (QoS) enumeration for task priority.
 *
 * Primarily used as a hint for iOS's DispatchQoS task priority system.
 * Android WorkManager handles priority automatically based on constraints.
 */
enum class Qos {
    /**
     * Utility QoS - Low priority, user is not waiting.
     *
     * **iOS**: `DispatchQoS.utility`
     * **Use Cases**: Prefetching, maintenance, non-urgent sync
     */
    Utility,

    /**
     * Background QoS - Default priority, deferrable work.
     *
     * **iOS**: `DispatchQoS.background`
     * **Use Cases**: Most background tasks, indexing, cleanup
     */
    Background,

    /**
     * User-Initiated QoS - Important work, user may be waiting.
     *
     * **iOS**: `DispatchQoS.userInitiated`
     * **Use Cases**: Explicit user action, data refresh from user request
     */
    UserInitiated,

    /**
     * User-Interactive QoS - Critical work, user actively waiting.
     *
     * **iOS**: `DispatchQoS.userInteractive`
     * **Use Cases**: UI updates, animations, immediate user-facing operations
     * **Note**: Avoid for background tasks (defeats purpose of background work)
     */
    UserInteractive
}

/**
 * Policy for handling a new task when one with the same ID already exists.
 *
 * **Both platforms**: Enforced at scheduling time
 */
enum class ExistingPolicy {
    /**
     * Keep the existing task and ignore the new request.
     *
     * **Android**: Uses `ExistingWorkPolicy.KEEP` / `ExistingPeriodicWorkPolicy.KEEP`
     * **iOS**: Checks metadata existence; if present, returns `ScheduleResult.ACCEPTED` without scheduling
     *
     * **Use Cases**: Ensure only one instance runs, prevent duplicate scheduling
     */
    KEEP,

    /**
     * Cancel the existing task and replace it with the new one.
     *
     * **Android**: Uses `ExistingWorkPolicy.REPLACE` / `ExistingPeriodicWorkPolicy.REPLACE`
     * **iOS**: Calls `cancel(id)` before scheduling new task
     *
     * **Use Cases**: Update task parameters, reschedule with new constraints
     */
    REPLACE
}

/**
 * Result of a task scheduling operation.
 *
 * Indicates whether the OS accepted, rejected, or throttled the request.
 */
enum class ScheduleResult {
    /**
     * The task was successfully enqueued by the OS scheduler.
     *
     * **Note**: Acceptance doesn't guarantee execution (constraints may not be met).
     */
    ACCEPTED,

    /**
     * The task was rejected due to OS limitation or policy.
     *
     * **Common Reasons**:
     * - Android: Missing `SCHEDULE_EXACT_ALARM` permission
     * - iOS: Task ID not in Info.plist `BGTaskSchedulerPermittedIdentifiers`
     * - Platform doesn't support trigger type (e.g., ContentUri on iOS)
     * - Invalid parameters (e.g., negative delay)
     */
    REJECTED_OS_POLICY,

    /**
     * The OS is currently throttling background work for this app.
     *
     * **Common Reasons**:
     * - Android: App in background restrictions
     * - Android: Too many failed tasks (exponential backoff in effect)
     * - iOS: App exceeded background execution budget
     * - iOS: Low Power Mode enabled
     *
     * **Recommendation**: Retry with exponential backoff or wait for better conditions
     */
    THROTTLED
}
