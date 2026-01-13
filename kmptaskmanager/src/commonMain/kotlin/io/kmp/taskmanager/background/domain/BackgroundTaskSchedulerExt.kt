package io.kmp.taskmanager.background.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Extension functions for BackgroundTaskScheduler to provide type-safe serialization.
 *
 * These extensions allow you to pass objects directly instead of manually converting to JSON strings.
 *
 * v4.1.0+: Sugar syntax for better developer experience
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class UploadData(val url: String, val size: Long)
 *
 * // Before (manual JSON conversion):
 * val data = UploadData("https://...", 1024)
 * val jsonString = Json.encodeToString(data)
 * scheduler.enqueue(..., inputJson = jsonString)
 *
 * // After (direct object passing):
 * scheduler.enqueue(
 *     id = "upload",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "UploadWorker",
 *     input = UploadData("https://...", 1024) // Type-safe!
 * )
 * ```
 */

/**
 * Enqueue a task with type-safe input serialization.
 *
 * This extension automatically serializes the input object to JSON using kotlinx.serialization.
 *
 * @param T The type of input data (must be annotated with @Serializable)
 * @param id Unique task identifier
 * @param trigger When and how the task should be triggered
 * @param workerClassName Fully qualified worker class name
 * @param constraints Execution constraints (network, charging, etc.)
 * @param input Optional input data (will be serialized to JSON automatically)
 * @param policy How to handle duplicate task IDs
 * @return ScheduleResult indicating success or failure
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class SyncRequest(val userId: String, val fullSync: Boolean)
 *
 * scheduler.enqueue(
 *     id = "user-sync-123",
 *     trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
 *     workerClassName = "SyncWorker",
 *     input = SyncRequest(userId = "123", fullSync = true),
 *     constraints = Constraints(requiresNetwork = true)
 * )
 * ```
 */
suspend inline fun <reified T> BackgroundTaskScheduler.enqueue(
    id: String,
    trigger: TaskTrigger,
    workerClassName: String,
    constraints: Constraints = Constraints(),
    input: T? = null,
    policy: ExistingPolicy = ExistingPolicy.KEEP
): ScheduleResult {
    val inputJson = input?.let { Json.encodeToString(it) }
    return enqueue(
        id = id,
        trigger = trigger,
        workerClassName = workerClassName,
        constraints = constraints,
        inputJson = inputJson,
        policy = policy
    )
}

/**
 * Begin a task chain with type-safe input serialization (single task).
 *
 * This extension automatically serializes the input object to JSON.
 *
 * @param T The type of input data (must be annotated with @Serializable)
 * @param workerClassName Fully qualified worker class name
 * @param constraints Execution constraints
 * @param input Optional input data (will be serialized to JSON automatically)
 * @return TaskChain for further chaining with then()
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class DownloadRequest(val url: String)
 *
 * scheduler.beginWith(
 *     workerClassName = "DownloadWorker",
 *     input = DownloadRequest("https://example.com/file.zip")
 * )
 *     .then(TaskRequest("ExtractWorker"))
 *     .then(TaskRequest("ProcessWorker"))
 *     .enqueue()
 * ```
 */
inline fun <reified T> BackgroundTaskScheduler.beginWith(
    workerClassName: String,
    constraints: Constraints = Constraints(),
    input: T? = null
): TaskChain {
    val inputJson = input?.let { Json.encodeToString(it) }
    val taskRequest = TaskRequest(
        workerClassName = workerClassName,
        constraints = constraints,
        inputJson = inputJson
    )
    return beginWith(taskRequest)
}

/**
 * Begin a task chain with type-safe input serialization (parallel tasks).
 *
 * This extension automatically serializes input objects to JSON for multiple tasks.
 *
 * @param tasks List of task specifications with typed input data
 * @return TaskChain for further chaining with then()
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class FetchRequest(val endpoint: String)
 *
 * scheduler.beginWith(
 *     TaskSpec("FetchUserWorker", input = FetchRequest("/users")),
 *     TaskSpec("FetchPostsWorker", input = FetchRequest("/posts"))
 * )
 *     .then(TaskRequest("MergeDataWorker"))
 *     .enqueue()
 * ```
 */
inline fun <reified T> BackgroundTaskScheduler.beginWith(
    vararg tasks: TaskSpec<T>
): TaskChain {
    val taskRequests = tasks.map { spec ->
        val inputJson = spec.input?.let { Json.encodeToString(it) }
        TaskRequest(
            workerClassName = spec.workerClassName,
            constraints = spec.constraints,
            inputJson = inputJson
        )
    }
    return beginWith(taskRequests)
}

/**
 * Type-safe task specification for parallel chain execution.
 *
 * @param T The type of input data (must be annotated with @Serializable)
 * @param workerClassName Fully qualified worker class name
 * @param constraints Execution constraints
 * @param input Optional typed input data
 */
data class TaskSpec<T>(
    val workerClassName: String,
    val constraints: Constraints = Constraints(),
    val input: T? = null
)
