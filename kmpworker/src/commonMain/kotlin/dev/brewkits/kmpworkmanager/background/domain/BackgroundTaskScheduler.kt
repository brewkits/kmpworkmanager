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
     * @return [ScheduleResult] indicating if the OS accepted the request.
     */
    suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints = Constraints(),
        inputJson: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    ): ScheduleResult

    /** Cancels a pending or running task by its unique ID. */
    fun cancel(id: String)

    /** Cancels all background tasks managed by this scheduler. */
    fun cancelAll()

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
