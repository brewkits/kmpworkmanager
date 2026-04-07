package dev.brewkits.kmpworkmanager.background.domain

/**
 * Main interface for scheduling background tasks.
 * Use this from your common code - it works on both Android and iOS.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a task to be executed in the background.
     */
    suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints = Constraints(),
        inputJson: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    ): ScheduleResult

    /** Cancels a specific pending task by its unique ID. */
    fun cancel(id: String)

    /** Cancels all previously scheduled tasks currently managed by the scheduler. */
    fun cancelAll()

    /**
     * Begins a new task chain with a single initial task.
     */
    fun beginWith(task: TaskRequest): TaskChain

    /**
     * Begins a new task chain with a group of tasks that will run in parallel.
     */
    fun beginWith(tasks: List<TaskRequest>): TaskChain

    /**
     * Enqueues a constructed [TaskChain] for execution.
     */
    suspend fun enqueueChain(
        chain: TaskChain,
        id: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    )

    /**
     * Flush all pending progress updates to disk immediately.
     */
    fun flushPendingProgress()

    /**
     * Returns the most recent task chain execution records, newest first.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord>

    /**
     * Deletes all stored execution history records.
     */
    suspend fun clearExecutionHistory()
}
