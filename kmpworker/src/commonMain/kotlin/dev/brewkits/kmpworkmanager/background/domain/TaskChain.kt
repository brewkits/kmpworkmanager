
package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * A single task that can be chained with other tasks.
 *
 * @property workerClassName Name of the worker class to run
 * @property inputJson Optional JSON data to pass to the worker
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
    fun withId(id: String, policy: ExistingPolicy = ExistingPolicy.REPLACE): TaskChain =
        copyWith(chainId = id, existingPolicy = policy)

    /**
     * Creates a copy of this chain with a new ID and/or policy, preserving all steps.
     * Replaces the awkward private dummy-parameter constructor pattern (v2.3.7+).
     */
    private fun copyWith(chainId: String?, existingPolicy: ExistingPolicy): TaskChain {
        val copy = TaskChain(scheduler, steps.first(), chainId, existingPolicy)
        copy.steps.clear()
        copy.steps.addAll(this.steps)
        return copy
    }

    /**
     * Enqueues the constructed task chain for execution.
     * The actual scheduling is delegated to the `BackgroundTaskScheduler`.
     *
     * **Breaking Change (v2.3.5):** This method is now suspending to prevent deadlock risks.
     *
     * Migration:
     * ```kotlin
     * // Before (v2.3.x):
     * fun scheduleChain() {
     *     val chain = scheduler.beginWith(task1).then(task2)
     *     chain.enqueue()  // Blocking
     * }
     *
     * // After (v2.3.5+):
     * suspend fun scheduleChain() {
     *     val chain = scheduler.beginWith(task1).then(task2)
     *     chain.enqueue()  // Suspending
     * }
     *
     * // Or wrap in coroutine:
     * fun scheduleChain() {
     *     CoroutineScope(Dispatchers.IO).launch {
     *         val chain = scheduler.beginWith(task1).then(task2)
     *         chain.enqueue()
     *     }
     * }
     * ```
     *
     * @since 2.3.4 Now suspending to prevent deadlock risks
     */
    suspend fun enqueue() {
        scheduler.enqueueChain(this, chainId, existingPolicy)
    }

    /**
     * Enqueues the constructed task chain for execution (blocking version).
     *
     * **DEPRECATED:** Use suspending `enqueue()` instead.
     * This blocking version is provided for backward compatibility only
     * and will be removed in v3.0.0.
     *
     * @deprecated Use suspending enqueue() to avoid blocking and potential deadlocks
     */
    @Deprecated(
        message = "Use suspending enqueue() instead. enqueueBlocking() causes ANR on Android main thread " +
            "and deadlocks when called from a coroutine. This method will be removed in v3.0.0.",
        replaceWith = ReplaceWith("enqueue()"),
        level = DeprecationLevel.ERROR
    )
    fun enqueueBlocking() {
        kotlinx.coroutines.runBlocking {
            scheduler.enqueueChain(this@TaskChain, chainId, existingPolicy)
        }
    }

    /**
     * Internal function to allow the platform-specific schedulers to access the steps of the chain.
     */
    internal fun getSteps(): List<List<TaskRequest>> = steps
}
