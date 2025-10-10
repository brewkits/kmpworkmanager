package com.example.kmpworkmanagerv2.background.domain

/**
 * The primary contract (interface) for all background scheduling operations.
 * The rest of the application should only interact with this interface, ensuring a clean, platform-agnostic architecture.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a task to be executed in the background.
     * @param id A unique identifier for the task, used for cancellation and replacement.
     * @param trigger The condition that will trigger the task execution.
     * @param workerClassName A unique name identifying the actual work (Worker/Job) to be done on the platform.
     * @param constraints Conditions that must be met for the task to run. Defaults to no constraints.
     * @param inputJson Optional JSON string data to pass as input to the worker. Defaults to null.
     * @param policy How to handle this request if a task with the same ID already exists. Defaults to REPLACE.
     * @return The result of the scheduling operation (ACCEPTED, REJECTED, THROTTLED).
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
}