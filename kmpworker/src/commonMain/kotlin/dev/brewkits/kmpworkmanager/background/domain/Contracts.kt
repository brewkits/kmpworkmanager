@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)
package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * Defines the trigger condition for a background task.
 *
 * This sealed interface provides a type-safe way to specify when and how
 * background tasks should be executed. Each trigger type has different
 * platform support and scheduling characteristics.
 *
 * Platform Support Matrix:
 * - Periodic, OneTime, Exact, Windowed: ✅ Android ✅ iOS
 * - ContentUri, Battery*, Storage*, DeviceIdle: ✅ Android only
 *
 * **Note on Windowed (iOS)**: iOS only supports `earliest` time via `earliestBeginDate`.
 * The `latest` time is logged but not enforced - iOS decides when to run opportunistically.
 */
sealed interface TaskTrigger {

    /**
     * Triggers at a precise moment in time using exact alarm.
     */
    data class Exact(val atEpochMillis: Long) : TaskTrigger

    /**
     * Triggers within a time window.
     */
    data class Windowed(val earliest: Long, val latest: Long) : TaskTrigger

    /**
     * Triggers periodically at regular intervals.
     *
     * @param intervalMs The interval between task executions, in milliseconds.
     * @param flexMs The optional flex window for the execution, in milliseconds (Android only).
     * @param initialDelayMs The delay before the very first execution, in milliseconds.
     *   **Note on iOS**: This is a best-effort delay enforced by the system's background budget.
     * @param runImmediately Whether to run the task immediately on first schedule.
     *   When `false` and [initialDelayMs] is 0, the first execution is deferred by one full
     *   [intervalMs]. This eliminates the common workaround of setting
     *   `initialDelayMs = intervalMs` just to prevent an immediate first run.
     *   If [initialDelayMs] is already > 0, this flag has no effect — the explicit delay is used.
     */
    data class Periodic(
        val intervalMs: Long,
        val flexMs: Long? = null,
        val initialDelayMs: Long = 0,
        val runImmediately: Boolean = true
    ) : TaskTrigger {
        init {
            require(runImmediately || initialDelayMs == 0L) {
                "Ambiguous: runImmediately=false has no effect when initialDelayMs=${initialDelayMs}ms is already set. Use one or the other."
            }
        }
    }

    /**
     * Triggers once after an optional initial delay.
     */
    data class OneTime(val initialDelayMs: Long = 0) : TaskTrigger

    /**
     * Triggers when a content URI changes - **ANDROID ONLY**.
     */
    @AndroidOnly
    data class ContentUri(
        val uriString: String,
        val triggerForDescendants: Boolean = false
    ) : TaskTrigger
}

/**
 * System-level constraints for task execution.
 */
@Serializable
enum class SystemConstraint {
    ALLOW_LOW_STORAGE,
    ALLOW_LOW_BATTERY,
    REQUIRE_BATTERY_NOT_LOW,
    DEVICE_IDLE
}

/**
 * Defines the constraints under which a background task can run.
 */
@Serializable
data class Constraints(
    val requiresNetwork: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val allowWhileIdle: Boolean = false,
    val qos: Qos = Qos.Background,
    val isHeavyTask: Boolean = false,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMs: Long = 30_000,
    val systemConstraints: Set<SystemConstraint> = emptySet(),
    val exactAlarmIOSBehavior: ExactAlarmIOSBehavior = ExactAlarmIOSBehavior.SHOW_NOTIFICATION,
    val extras: Map<String, String> = emptyMap()
)

/**
 * Backoff policy for task retry behavior.
 */
enum class BackoffPolicy {
    LINEAR,
    EXPONENTIAL
}

/**
 * Quality of Service (QoS) enumeration for task priority.
 */
enum class Qos {
    Utility,
    Background,
    UserInitiated,
    UserInteractive
}

/**
 * iOS-specific behavior for TaskTrigger.Exact alarms.
 */
enum class ExactAlarmIOSBehavior {
    SHOW_NOTIFICATION,
    ATTEMPT_BACKGROUND_RUN,
    THROW_ERROR
}

/**
 * Policy for handling a new task when one with the same ID already exists.
 */
enum class ExistingPolicy {
    KEEP,
    REPLACE
}

/**
 * Result of a task scheduling operation.
 */
enum class ScheduleResult {
    ACCEPTED,
    REJECTED_OS_POLICY,
    DEADLINE_ALREADY_PASSED,
    THROTTLED
}
