package dev.brewkits.kmpworkmanager.testing

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger

/**
 * Test double for [BackgroundTaskScheduler].
 *
 * Captures all scheduling calls so tests can assert on them without touching
 * real Android WorkManager or iOS BGTaskScheduler.
 *
 * **Usage:**
 * ```kotlin
 * val fakeScheduler = FakeBackgroundTaskScheduler()
 *
 * // Exercise code under test
 * myViewModel.scheduleSync(fakeScheduler)
 *
 * // Assert
 * assertEquals(1, fakeScheduler.enqueuedTasks.size)
 * assertEquals("SyncWorker", fakeScheduler.enqueuedTasks.first().workerClassName)
 * assertTrue(fakeScheduler.cancelledIds.isEmpty())
 * ```
 *
 * @since 2.3.7
 */
class FakeBackgroundTaskScheduler(
    /** Default result returned by [enqueue]. Override per test if needed. */
    private val defaultResult: ScheduleResult = ScheduleResult.ACCEPTED
) : BackgroundTaskScheduler {

    // ── Recorded calls ────────────────────────────────────────────────────────

    /** All tasks passed to [enqueue], in call order. */
    val enqueuedTasks: MutableList<EnqueuedTask> = mutableListOf()

    /** IDs passed to [cancel]. */
    val cancelledIds: MutableList<String> = mutableListOf()

    /** True if [cancelAll] was called at least once. */
    var cancelAllCalled: Boolean = false
        private set

    /** All chains passed to [enqueueChain], in call order. */
    val enqueuedChains: MutableList<EnqueuedChain> = mutableListOf()

    /** Number of times [flushPendingProgress] was called. */
    var flushCount: Int = 0
        private set

    // ── BackgroundTaskScheduler implementation ─────────────────────────────

    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        enqueuedTasks += EnqueuedTask(id, trigger, workerClassName, constraints, inputJson, policy)
        return defaultResult
    }

    override fun cancel(id: String) {
        cancelledIds += id
    }

    override fun cancelAll() {
        cancelAllCalled = true
    }

    override fun flushPendingProgress() {
        flushCount++
    }

    override fun beginWith(task: TaskRequest): TaskChain =
        TaskChain(this, listOf(task))

    override fun beginWith(tasks: List<TaskRequest>): TaskChain =
        TaskChain(this, tasks)

    override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {
        enqueuedChains += EnqueuedChain(chain, id, policy)
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    /** Clears all recorded state. Useful for reuse across test cases. */
    fun reset() {
        enqueuedTasks.clear()
        cancelledIds.clear()
        cancelAllCalled = false
        enqueuedChains.clear()
        flushCount = 0
    }

    /** Returns true if a task with [workerClassName] was enqueued. */
    fun hasEnqueued(workerClassName: String): Boolean =
        enqueuedTasks.any { it.workerClassName == workerClassName }

    /** Returns true if a task with [id] was cancelled. */
    fun hasCancelled(id: String): Boolean = id in cancelledIds

    // ── Data classes ───────────────────────────────────────────────────────

    data class EnqueuedTask(
        val id: String,
        val trigger: TaskTrigger,
        val workerClassName: String,
        val constraints: Constraints,
        val inputJson: String?,
        val policy: ExistingPolicy
    )

    data class EnqueuedChain(
        val chain: TaskChain,
        val id: String?,
        val policy: ExistingPolicy
    )
}
