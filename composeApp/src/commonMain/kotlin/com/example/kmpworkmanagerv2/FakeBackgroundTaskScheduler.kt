package com.example.kmpworkmanagerv2

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger

/**
 * A fake implementation of BackgroundTaskScheduler for use in previews, tests, or where
 * no actual background scheduling is required.
 */
class FakeBackgroundTaskScheduler : BackgroundTaskScheduler {
    /**
     * Always returns ScheduleResult.ACCEPTED immediately without actually scheduling a task.
     */
    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        return ScheduleResult.ACCEPTED
    }

    /**
     * No operation (No-op) for cancellation.
     */
    override fun cancel(id: String) {}

    /**
     * No operation (No-op) for cancelling all tasks.
     */
    override fun cancelAll() {}
}