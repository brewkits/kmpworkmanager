package dev.brewkits.kmpworkmanager.sample

import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.Constraints
import dev.brewkits.kmpworkmanager.sample.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.sample.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger

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
