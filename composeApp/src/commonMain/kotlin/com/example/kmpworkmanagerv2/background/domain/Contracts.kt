package com.example.kmpworkmanagerv2.background.domain

/**
 * Defines the trigger condition for a background task.
 */
sealed interface TaskTrigger {
    /** Triggers at a precise moment in time. Intended for user-facing events like alarms. */
    data class Exact(val atEpochMillis: Long) : TaskTrigger

    /** Triggers within a time window. Allows the OS to optimize execution. */
    data class Windowed(val earliest: Long, val latest: Long) : TaskTrigger

    /** Triggers periodically. */
    data class Periodic(val intervalMs: Long, val flexMs: Long? = null) : TaskTrigger
    data class OneTime(val initialDelayMs: Long = 0) : TaskTrigger
}

/**
 * Defines the constraints under which a task can run.
 */
data class Constraints(
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val allowWhileIdle: Boolean = false, // Hint for Android Doze mode
    val qos: Qos = Qos.Background,        // Hint for iOS task priority
    val isHeavyTask: Boolean = false
)

enum class Qos { Utility, Background, UserInitiated, UserInteractive }

/**
 * Defines the policy for handling a new task when one with the same ID already exists.
 */
enum class ExistingPolicy {
    KEEP,    // Keep the existing task and ignore the new one.
    REPLACE, // Cancel the existing task and replace it with the new one.
}

/**
 * Represents the result of a scheduling operation.
 */
enum class ScheduleResult {
    ACCEPTED,           // The task was successfully enqueued.
    REJECTED_OS_POLICY, // The task was rejected due to an OS policy (e.g., interval too short).
    THROTTLED           // The OS is throttling background work.
}