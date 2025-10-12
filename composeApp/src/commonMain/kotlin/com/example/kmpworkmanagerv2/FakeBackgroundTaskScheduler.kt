package com.example.kmpworkmanagerv2

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskChain
import com.example.kmpworkmanagerv2.background.domain.TaskRequest
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger

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
