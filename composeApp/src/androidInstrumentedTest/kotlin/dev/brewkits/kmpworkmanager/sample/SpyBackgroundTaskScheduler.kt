package dev.brewkits.kmpworkmanager.sample

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger

/**
 * A [BackgroundTaskScheduler] that records every call instead of touching WorkManager —
 * used by UI tests that need to assert *what* the UI asked the scheduler to do (id,
 * trigger, constraints, tags) without a real WorkManager/BGTaskScheduler round-trip.
 *
 * Distinct from [dev.brewkits.kmpworkmanager.sample.FakeBackgroundTaskScheduler] (which is
 * silent, for previews) — this one is a test spy with assertable call history.
 */
class SpyBackgroundTaskScheduler : BackgroundTaskScheduler {
    data class EnqueueCall(
        val id: String,
        val trigger: TaskTrigger,
        val workerClassName: String,
        val constraints: Constraints,
        val inputJson: String?,
        val policy: ExistingPolicy,
        val tags: Set<String>,
        val deadlineMs: Long?
    )

    data class ChainCall(val chain: TaskChain, val id: String?, val policy: ExistingPolicy)

    val enqueueCalls = mutableListOf<EnqueueCall>()
    val chainCalls = mutableListOf<ChainCall>()
    val cancelledIds = mutableListOf<String>()

    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy,
        tags: Set<String>,
        deadlineMs: Long?
    ): ScheduleResult {
        enqueueCalls += EnqueueCall(id, trigger, workerClassName, constraints, inputJson, policy, tags, deadlineMs)
        return ScheduleResult.ACCEPTED
    }

    override fun cancel(id: String) {
        cancelledIds += id
    }

    override fun cancelAll() {}

    override fun cancelByTag(tag: String) {}

    override fun cancelByWorkerClass(workerClassName: String) {}

    override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))

    override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)

    override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
        chainCalls += ChainCall(chain, id, policy)
    }

    override fun flushPendingProgress() {}

    override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()

    override suspend fun clearExecutionHistory() {}
}
