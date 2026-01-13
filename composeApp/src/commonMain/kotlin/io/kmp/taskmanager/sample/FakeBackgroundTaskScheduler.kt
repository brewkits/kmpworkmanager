package io.kmp.taskmanager.sample

import io.kmp.taskmanager.sample.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.sample.background.domain.Constraints
import io.kmp.taskmanager.sample.background.domain.ExistingPolicy
import io.kmp.taskmanager.sample.background.domain.ScheduleResult
import io.kmp.taskmanager.sample.background.domain.TaskChain
import io.kmp.taskmanager.sample.background.domain.TaskRequest
import io.kmp.taskmanager.sample.background.domain.TaskTrigger

class FakeBackgroundTaskScheduler : BackgroundTaskScheduler {
    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        println("FakeBackgroundTaskScheduler: Enqueue called for $id")
        return ScheduleResult.ACCEPTED
    }

    override fun cancel(id: String) {
        println("FakeBackgroundTaskScheduler: Cancel called for $id")
    }

    override fun cancelAll() {
        println("FakeBackgroundTaskScheduler: CancelAll called")
    }

    override fun beginWith(task: TaskRequest): TaskChain {
        println("FakeBackgroundTaskScheduler: beginWith(task) called")
        return TaskChain(this, listOf(task))
    }

    override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        println("FakeBackgroundTaskScheduler: beginWith(tasks) called")
        return TaskChain(this, tasks)
    }

    override fun enqueueChain(chain: TaskChain) {
        println("FakeBackgroundTaskScheduler: enqueueChain called")
    }
}
