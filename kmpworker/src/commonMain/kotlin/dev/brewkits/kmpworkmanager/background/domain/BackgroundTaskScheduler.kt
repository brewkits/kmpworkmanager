package dev.brewkits.kmpworkmanager.background.domain

/**
 * The primary entry point for scheduling background work in KMP WorkManager.
 *
 * This interface provides a unified API for both Android (WorkManager) and iOS (BGTaskScheduler).
 * All methods are thread-safe and can be called from any coroutine context.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a standalone task for execution.
     *
     * @param id A unique identifier for the task. If a task with this ID already exists,
     * the behavior depends on the [policy] (default: REPLACE).
     * @param trigger Defines when the task should run (e.g., OneTime, Periodic).
     * @param workerClassName The fully qualified name of your worker class.
     * @param constraints Optional conditions like network or charging requirements.
     * @param inputJson Optional data to pass to the worker.
     * @param policy Determines what happens if a task with the same ID is already pending.
     * @param tags User-defined labels for group cancellation via [cancelByTag]. Independent of
     * [workerClassName] — tasks of different worker types can share a business-context tag.
     * See [TaskRequest.tags] for the chain-step equivalent.
     * @param deadlineMs Optional Unix epoch millisecond deadline. If the task has not started
     * by this time it is skipped instead of executed, so a delayed run cannot produce stale
     * data. See [TaskRequest.deadlineMs] for the chain-step equivalent.
     * @return [ScheduleResult] indicating if the OS accepted the request.
     */
    suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints = Constraints(),
        inputJson: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE,
        tags: Set<String> = emptySet(),
        deadlineMs: Long? = null
    ): ScheduleResult

    /** Cancels a pending or running task by its unique ID. */
    fun cancel(id: String)

    /** Cancels all background tasks managed by this scheduler. */
    fun cancelAll()

    /**
     * Cancels all pending or running tasks that carry the given user-defined tag.
     *
     * Matches both standalone tasks (see the `tags` parameter of [enqueue]) and chain steps
     * (see [TaskRequest.tags]); a caller thinks in terms of "cancel my work", not in terms
     * of which API scheduled it.
     *
     * ```kotlin
     * // Cancel every task related to a user session, whatever worker it uses:
     * scheduler.cancelByTag("user-123")
     * ```
     *
     * **Not supported for [TaskTrigger.Exact]** on Android: exact alarms run through
     * `AlarmManager` rather than WorkManager and are not tag-indexed. Cancel those by id.
     *
     * Has a no-op default so that adding this method does not break existing third-party
     * implementations of this interface (test doubles in particular). Real schedulers
     * override it; the default logs nothing and cancels nothing.
     */
    fun cancelByTag(tag: String) {
        // Intentionally empty: see KDoc. A custom scheduler that never implemented tagging
        // has nothing to cancel, and throwing here would break callers of a working app.
    }

    /**
     * Cancels all pending or running tasks whose worker class name matches
     * [workerClassName] — standalone tasks and chain steps alike.
     *
     * Grouping is by worker *type* rather than by business context; use [cancelByTag] when
     * you need to cancel a set of differently-typed workers that belong to one operation.
     *
     * ```kotlin
     * scheduler.cancelByWorkerClass("SyncWorker")
     * ```
     *
     * Has a no-op default for the same source-compatibility reason as [cancelByTag].
     */
    fun cancelByWorkerClass(workerClassName: String) {
        // Intentionally empty: see cancelByTag.
    }

    /**
     * Starts building a sequential or parallel task chain.
     * Use this for complex workflows where order matters.
     */
    fun beginWith(task: TaskRequest): TaskChain

    /** Starts building a task chain where the first step executes multiple tasks in parallel. */
    fun beginWith(tasks: List<TaskRequest>): TaskChain

    /**
     * Submits a completed [TaskChain] to the native scheduler.
     */
    suspend fun enqueueChain(
        chain: TaskChain,
        id: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    )

    /**
     * Forces an immediate flush of pending task progress to persistent storage.
     * Useful for critical data updates before app suspension.
     */
    fun flushPendingProgress()

    /**
     * Returns a list of recent task execution results, newest first.
     * Useful for debugging or displaying status in the UI.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord>

    /** Clears all execution history from local storage. */
    suspend fun clearExecutionHistory()
}
