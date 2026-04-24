@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package dev.brewkits.kmpworkmanager.background.domain

/**
 * Helper function to create a TaskTrigger.OneTime instance from Swift/Objective-C.
 *
 * @param initialDelayMs The delay before the task should run, in milliseconds.
 * @return A TaskTrigger.OneTime object.
 */
fun createTaskTriggerOneTime(initialDelayMs: Long): TaskTrigger {
    return TaskTrigger.OneTime(initialDelayMs)
}

/**
 * iOS-friendly helper function to create a TaskTrigger.OneTime using seconds.
 */
fun createTaskTriggerOneTimeSeconds(initialDelaySeconds: Double): TaskTrigger {
    return TaskTrigger.OneTime((initialDelaySeconds * 1000).toLong())
}

/**
 * Helper function to create a TaskTrigger.Periodic instance from Swift/Objective-C.
 *
 * @param intervalMs The interval between task executions, in milliseconds.
 * @param flexMs The optional flex window for the execution, in milliseconds.
 * @param initialDelayMs The delay before the first execution, in milliseconds.
 * @param runImmediately When `false` and [initialDelayMs] is 0, the first run is deferred
 *   by one full [intervalMs]. Has no effect when [initialDelayMs] > 0.
 * @return A TaskTrigger.Periodic object.
 */
fun createTaskTriggerPeriodic(
    intervalMs: Long,
    flexMs: Long? = null,
    initialDelayMs: Long = 0,
    runImmediately: Boolean = true
): TaskTrigger {
    return TaskTrigger.Periodic(intervalMs, flexMs, initialDelayMs, runImmediately)
}

/**
 * iOS-friendly helper function to create a TaskTrigger.Periodic using seconds.
 *
 * @param runImmediately When `false` and [initialDelaySeconds] is 0, the first run is deferred
 *   by one full [intervalSeconds]. Has no effect when [initialDelaySeconds] > 0.
 */
fun createTaskTriggerPeriodicSeconds(
    intervalSeconds: Double,
    initialDelaySeconds: Double = 0.0,
    runImmediately: Boolean = true
): TaskTrigger {
    return TaskTrigger.Periodic(
        intervalMs = (intervalSeconds * 1000).toLong(),
        initialDelayMs = (initialDelaySeconds * 1000).toLong(),
        runImmediately = runImmediately
    )
}

/**
 * Helper function to create a Constraints instance with default values from Swift/Objective-C.
 *
 * @return A Constraints object with all fields set to their default (false/Background).
 */
fun createConstraints(): Constraints {
    return Constraints()
}
