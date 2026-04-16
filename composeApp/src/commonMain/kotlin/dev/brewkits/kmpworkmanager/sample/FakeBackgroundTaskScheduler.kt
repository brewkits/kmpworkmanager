package dev.brewkits.kmpworkmanager.sample

import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger

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

    override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
        println("FakeBackgroundTaskScheduler: enqueueChain called with id=$id, policy=$policy")
    }

    override fun flushPendingProgress() {
        println("FakeBackgroundTaskScheduler: flushPendingProgress called")
    }

    override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()

    override suspend fun clearExecutionHistory() {}
}
