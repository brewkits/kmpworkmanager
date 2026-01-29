
package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * Represents a single, non-periodic task to be executed as part of a chain.
 *
 * @property workerClassName A unique name identifying the actual work to be done.
 * @property inputJson Optional JSON string data to pass as input to the worker.
 */
@Serializable
data class TaskRequest(
    val workerClassName: String,
    val inputJson: String? = null,
    val constraints: Constraints? = null
)

/**
 * A builder class for creating a chain of background tasks.
 *
 * This class is not meant to be instantiated directly. Use `BackgroundTaskScheduler.beginWith()` to start a chain.
 * It allows for creating sequential and parallel groups of tasks.
 *
 * @property scheduler The scheduler instance used to enqueue the chain.
 * @property steps A mutable list where each element is a list of tasks to be run in parallel at that step.
 */
class TaskChain internal constructor(
    private val scheduler: BackgroundTaskScheduler,
    initialTasks: List<TaskRequest>,
    internal val chainId: String? = null,
    internal val existingPolicy: ExistingPolicy = ExistingPolicy.REPLACE
) {
    private val steps: MutableList<List<TaskRequest>> = mutableListOf(initialTasks)

    /**
     */
    @Suppress("UNUSED_PARAMETER")
    private constructor(
        scheduler: BackgroundTaskScheduler,
        steps: MutableList<List<TaskRequest>>,
        chainId: String?,
        existingPolicy: ExistingPolicy,
        @Suppress("UNUSED_PARAMETER") dummy: Unit = Unit
    ) : this(scheduler, steps.firstOrNull() ?: emptyList(), chainId, existingPolicy) {
        // Replace the single-item steps with the full steps list
        this.steps.clear()
        this.steps.addAll(steps)
    }

    /**
     * Appends a single task to be executed sequentially after all previous tasks in the chain have completed.
     *
     * @param task The [TaskRequest] to add to the chain.
     * @return The current [TaskChain] instance for fluent chaining.
     */
    fun then(task: TaskRequest): TaskChain {
        steps.add(listOf(task))
        return this
    }

    /**
     * Appends a group of tasks to be executed in parallel after all previous tasks in the chain have completed.
     *
     * @param tasks A list of [TaskRequest]s to add to the chain.
     * @return The current [TaskChain] instance for fluent chaining.
     * @throws IllegalArgumentException if the tasks list is empty.
     */
    fun then(tasks: List<TaskRequest>): TaskChain {
        require(tasks.isNotEmpty()) { "Task list for 'then' cannot be empty." }
        steps.add(tasks)
        return this
    }

    /**
     * Sets a unique ID for this chain and specifies the ExistingPolicy.
     *
     * @param id Unique identifier for the chain
     * @param policy How to handle if a chain with this ID already exists
     * @return A new [TaskChain] instance with the specified ID and policy
     */
    fun withId(id: String, policy: ExistingPolicy = ExistingPolicy.REPLACE): TaskChain {
        return TaskChain(scheduler, steps.toMutableList(), id, policy, Unit)
    }

    /**
     * Enqueues the constructed task chain for execution.
     * The actual scheduling is delegated to the `BackgroundTaskScheduler`.
     */
    fun enqueue() {
        scheduler.enqueueChain(this, chainId, existingPolicy)
    }

    /**
     * Internal function to allow the platform-specific schedulers to access the steps of the chain.
     */
    internal fun getSteps(): List<List<TaskRequest>> = steps
}
