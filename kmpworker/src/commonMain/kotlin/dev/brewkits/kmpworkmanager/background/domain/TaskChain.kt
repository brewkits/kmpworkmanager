package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * A TaskRequest represents a single unit of work to be scheduled.
 *
 * @property workerClassName The class name of the worker to execute.
 * @property inputJson Optional JSON string passed to the worker's `doWork()` call.
 * @property constraints Optional per-step constraints. If null, the chain-level constraints apply.
 * @property priority Execution priority relative to other tasks in the queue.
 * @property isIdempotent Whether this task can be safely retried from the beginning after a crash.
 *   Set to `false` for tasks with irreversible side-effects (e.g. payment, email send).
 *   A chain with non-idempotent tasks is **quarantined** rather than restarted after a corrupt-progress
 *   self-heal, preventing accidental double-execution.
 * @property tags A set of user-defined labels for group cancellation.
 *   Use `scheduler.cancelByTag("user-123")` to cancel all tasks carrying a given tag
 *   regardless of worker class. Tags are independent of `workerClassName` — you can mix
 *   workers of different types under the same business-context tag.
 *   ```kotlin
 *   // Enqueue tasks tagged with a user session
 *   TaskRequest("SyncWorker", tags = setOf("user-123", "background-sync"))
 *   TaskRequest("UploadWorker", tags = setOf("user-123"))
 *
 *   // Cancel everything for that user in one call:
 *   scheduler.cancelByTag("user-123")
 *   ```
 * @property deadlineMs Optional Unix epoch millisecond deadline. If the task has not started
 *   by this time, it is silently skipped with status `SKIPPED` instead of executing.
 *   Useful for time-sensitive operations (push notification sync, pre-flight checks) that
 *   produce stale data if they run too late.
 *   - `null` (default) — no deadline, task always executes when scheduled.
 *   - Affects both standalone tasks and chain steps.
 * @property mergeOutputFromPreviousStep When `true`, the `JsonObject` output from the
 *   immediately preceding step's `WorkerResult.Success.data` is merged into this task's
 *   `inputJson` before `doWork()` is called. Merged fields from the previous step
 *   **overwrite** same-key fields in the original `inputJson`.
 *   Use this to build data-pipeline chains without an external store:
 *   ```kotlin
 *   scheduler
 *       .beginWith(TaskRequest("DownloadWorker"))         // produces: {"filePath": "/tmp/x.zip"}
 *       .then(TaskRequest("ValidateWorker",
 *           mergeOutputFromPreviousStep = true))           // receives: {"filePath": "/tmp/x.zip"}
 *       .then(TaskRequest("UploadWorker",
 *           mergeOutputFromPreviousStep = true))           // receives: {"filePath": "/tmp/x.zip"}
 *       .enqueue()
 *   ```
 *   Default: `false` — opt-in to preserve backward compatibility.
 */
@Serializable
data class TaskRequest(
    val workerClassName: String,
    val inputJson: String? = null,
    val constraints: Constraints? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val isIdempotent: Boolean = true,
    val tags: Set<String> = emptySet(),
    val deadlineMs: Long? = null,
    val mergeOutputFromPreviousStep: Boolean = false
) {
    init {
        tags.forEach { validateTaskTag(it) }
    }
}

/**
 * Rejects tags that cannot round-trip through both platforms' tag storage.
 *
 * Validation lives at the API boundary on purpose: a tag that survives scheduling but
 * cannot be matched later would make [BackgroundTaskScheduler.cancelByTag] silently
 * fail to cancel — the worst possible failure mode for a cancellation API. Failing at
 * enqueue time turns that into an immediate, obvious programming error.
 *
 * Rules, and why each exists:
 * - **Non-blank** — a blank tag matches nothing and is always a bug.
 * - **No commas** — iOS persists a standalone task's tags as one comma-separated
 *   metadata string ([DynamicTaskDispatcher.META_TAGS]); an embedded comma would split
 *   one tag into two.
 * - **Max 100 chars** — Android stores tags in WorkManager's SQLite tag table; this keeps
 *   a runaway tag from bloating every row.
 *
 * @throws IllegalArgumentException if [tag] violates any rule.
 */
internal fun validateTaskTag(tag: String) {
    require(tag.isNotBlank()) { "Task tag must not be blank." }
    require(',' !in tag) {
        "Task tag must not contain a comma (got: '$tag'). Commas are the separator used to " +
            "persist tags in iOS task metadata, so an embedded comma would split the tag."
    }
    require(tag.length <= MAX_TASK_TAG_LENGTH) {
        "Task tag must be at most $MAX_TASK_TAG_LENGTH characters (got ${tag.length}: '${tag.take(30)}…')."
    }
}

/** Maximum length of a single task tag. See [validateTaskTag]. */
internal const val MAX_TASK_TAG_LENGTH = 100

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
        // Fail fast at the call site (BackgroundTaskScheduler.beginWith) rather than
        // silently building a zero-step chain — the old silent-no-op behavior meant a caller
        // whose task list ended up empty (e.g. after their own filtering) got no error here,
        // only a confusing no-op deep inside the platform-specific enqueueChain() much later.
        require(initialTasks.isNotEmpty()) { "beginWith() requires at least one task" }
        this.scheduler = scheduler
        this.steps = mutableListOf()
        steps.add(initialTasks)
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
