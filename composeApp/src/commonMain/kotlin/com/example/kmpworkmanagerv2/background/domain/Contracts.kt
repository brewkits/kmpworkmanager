package com.example.kmpworkmanagerv2.background.domain

/**
 * Defines the trigger condition for a background task.
 * This sealed interface allows for different types of scheduling requests.
 */
sealed interface TaskTrigger {
    /** Triggers at a precise moment in time. Intended for user-facing events like alarms/reminders. */
    data class Exact(val atEpochMillis: Long) : TaskTrigger

    /** Triggers within a time window (Android only). Allows the OS to optimize execution. */
    data class Windowed(val earliest: Long, val latest: Long) : TaskTrigger

    /** Triggers periodically (repeatedly) with an optional flex window. */
    data class Periodic(val intervalMs: Long, val flexMs: Long? = null) : TaskTrigger

    /** Triggers once after an optional initial delay. */
    data class OneTime(val initialDelayMs: Long = 0) : TaskTrigger
}

/**
 * Defines the constraints under which a task can run.
 */
data class Constraints(
    val requiresNetwork: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false, // Requires unmetered network (e.g., Wi-Fi) on Android
    val requiresCharging: Boolean = false,
    val allowWhileIdle: Boolean = false,          // Hint for Android Doze mode, allows running during device idle
    val qos: Qos = Qos.Background,                // Quality of Service hint for iOS task priority
    val isHeavyTask: Boolean = false              // Flag to use Foreground Service (Android) or BGProcessingTask (iOS)
)

/**
 * Quality of Service (Qos) enumeration, primarily used as a hint for iOS's task priority.
 */
enum class Qos {
    Utility,
    Background,
    UserInitiated,
    UserInteractive
}

/**
 * Defines the policy for handling a new task when one with the same ID already exists.
 */
enum class ExistingPolicy {
    KEEP,    // Keep the existing task and ignore the new request.
    REPLACE, // Cancel the existing task and replace it with the new one.
}

/**
 * Represents the result of a scheduling operation.
 */
enum class ScheduleResult {
    ACCEPTED,           // The task was successfully enqueued by the OS scheduler.
    REJECTED_OS_POLICY, // The task was rejected due to an OS limitation or policy (e.g., interval too short, missing permission).
    THROTTLED           // The OS is currently throttling background work for the application.
}