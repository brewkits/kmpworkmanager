package com.example.kmpworkmanagerv2.background.domain

/**
 * The primary contract (interface) for all background scheduling operations.
 * The rest of the app will only interact with this interface, ensuring a clean architecture.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a task to be executed in the background.
     * @param id A unique identifier for the task, used for cancellation and replacement.
     * @param trigger The condition that will trigger the task.
     * @param workerClassName A unique name identifying the work to be done.
     * @param constraints Conditions that must be met for the task to run.
     * @param inputJson Optional JSON string data to pass to the worker.
     * @param policy How to handle this request if a task with the same ID already exists.
     * @return The result of the scheduling operation.
     */
    suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints = Constraints(),
        inputJson: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    ): ScheduleResult

    /** Cancels a task by its unique ID. */
    fun cancel(id: String)

    /** Cancels all previously scheduled tasks. */
    fun cancelAll()
}