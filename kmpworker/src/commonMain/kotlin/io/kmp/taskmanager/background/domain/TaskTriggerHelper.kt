package io.kmp.taskmanager.background.domain

/**
 * Helper function to create a TaskTrigger.OneTime instance from Swift/Objective-C,
 * as default values in data classes are sometimes complex to access from native code.
 *
 * @param initialDelayMs The delay before the task should run, in milliseconds.
 * @return A TaskTrigger.OneTime object.
 */
fun createTaskTriggerOneTime(initialDelayMs: Long): TaskTrigger {
    return TaskTrigger.OneTime(initialDelayMs)
}

/**
 * Helper function to create a Constraints instance with default values from Swift/Objective-C.
 *
 * @return A Constraints object with all fields set to their default (false/Background).
 */
fun createConstraints(): Constraints {
    return Constraints()
}