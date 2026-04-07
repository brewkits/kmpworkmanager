package dev.brewkits.kmpworkmanager.background.domain

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Extension methods for [BackgroundTaskScheduler] to simplify common use cases.
 */

/**
 * Helper to enqueue a simple one-time task with no input.
 */
suspend fun BackgroundTaskScheduler.enqueueOneTime(
    id: String,
    workerClassName: String,
    initialDelayMs: Long = 0,
    constraints: Constraints = Constraints(),
    policy: ExistingPolicy = ExistingPolicy.REPLACE
): ScheduleResult {
    return this.enqueue(
        id = id,
        trigger = TaskTrigger.OneTime(initialDelayMs),
        workerClassName = workerClassName,
        constraints = constraints,
        policy = policy
    )
}

/**
 * Helper to enqueue a periodic task.
 */
suspend fun BackgroundTaskScheduler.enqueuePeriodic(
    id: String,
    workerClassName: String,
    intervalMs: Long,
    constraints: Constraints = Constraints(),
    policy: ExistingPolicy = ExistingPolicy.KEEP
): ScheduleResult {
    return this.enqueue(
        id = id,
        trigger = TaskTrigger.Periodic(intervalMs),
        workerClassName = workerClassName,
        constraints = constraints,
        policy = policy
    )
}
