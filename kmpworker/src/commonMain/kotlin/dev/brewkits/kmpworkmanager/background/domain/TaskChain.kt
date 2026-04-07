package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * A TaskRequest represents a single unit of work to be scheduled.
 */
@Serializable
data class TaskRequest(
    val workerClassName: String,
    val inputJson: String? = null,
    val constraints: Constraints? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val isIdempotent: Boolean = true
)

/**
 * TaskChain builder for sequential and parallel task execution.
 */
class TaskChain {
    private val scheduler: BackgroundTaskScheduler
    private val steps: MutableList<List<TaskRequest>>
    private var chainId: String? = null
    private var existingPolicy: ExistingPolicy = ExistingPolicy.REPLACE

    constructor(
        scheduler: BackgroundTaskScheduler,
        initialTasks: List<TaskRequest>
    ) {
        this.scheduler = scheduler
        this.steps = mutableListOf()
        if (initialTasks.isNotEmpty()) {
            steps.add(initialTasks)
        }
    }

    private constructor(
        scheduler: BackgroundTaskScheduler,
        steps: MutableList<List<TaskRequest>>,
        chainId: String?,
        existingPolicy: ExistingPolicy
    ) {
        this.scheduler = scheduler
        this.steps = steps
        this.chainId = chainId
        this.existingPolicy = existingPolicy
    }

    /**
     * Appends a single task to be executed after all previous steps complete.
     */
    fun then(task: TaskRequest): TaskChain {
        steps.add(listOf(task))
        return this
    }

    /**
     * Appends multiple tasks to be executed in parallel after all previous steps complete.
     */
    fun then(tasks: List<TaskRequest>): TaskChain {
        if (tasks.isNotEmpty()) {
            steps.add(tasks)
        }
        return this
    }

    /**
     * Sets a unique ID for this chain and specifies the ExistingPolicy.
     *
     * @param id Unique identifier for the chain
     * @param policy How to handle if a chain with this ID already exists
     * @return This TaskChain instance with the specified ID and policy
     */
    fun withId(id: String, policy: ExistingPolicy = ExistingPolicy.REPLACE): TaskChain {
        this.chainId = id
        this.existingPolicy = policy
        return this
    }

    /**
     * Enqueues the entire chain for execution.
     *
     * @param id Optional unique ID for the chain (overrides any ID set via withId)
     * @param policy How to handle if a chain with this ID already exists (overrides any policy set via withId)
     */
    suspend fun enqueue(
        id: String? = null,
        policy: ExistingPolicy? = null
    ) {
        val finalId = id ?: this.chainId
        val finalPolicy = policy ?: this.existingPolicy
        scheduler.enqueueChain(this, finalId, finalPolicy)
    }

    /**
     * Returns the steps of the chain. Internal use only.
     */
    internal fun getSteps(): List<List<TaskRequest>> = steps
}
