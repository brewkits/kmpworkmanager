package com.example.kmpworkmanagerv2.background.domain

import kotlinx.serialization.Serializable

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

    /**
     * Triggers when a content URI changes (Android only via ContentObserver).
     * On iOS, this will be rejected as unsupported.
     * @param uriString The content URI to observe (e.g., "content://media/external/images/media")
     * @param triggerForDescendants If true, triggers for changes in descendant URIs as well
     */
    data class ContentUri(
        val uriString: String,
        val triggerForDescendants: Boolean = false
    ) : TaskTrigger

    /**
     * Triggers when device storage is low (Android only).
     * On iOS, this will be rejected as unsupported.
     * Note: This is a system broadcast and may not trigger immediately.
     */
    data object StorageLow : TaskTrigger

    /**
     * Triggers when battery is low (Android only).
     * On iOS, this will be rejected as unsupported.
     * Note: "Low" is defined by the system (typically around 15%).
     */
    data object BatteryLow : TaskTrigger

    /**
     * Triggers when battery is okay/not low (Android only).
     * On iOS, this will be rejected as unsupported.
     */
    data object BatteryOkay : TaskTrigger

    /**
     * Triggers when device is idle/dozing (Android only).
     * On iOS, this will be rejected as unsupported.
     * Requires BIND_DEVICE_ADMIN permission (or similar).
     */
    data object DeviceIdle : TaskTrigger
}

/**
 * Defines the constraints under which a task can run.
 */
@Serializable
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