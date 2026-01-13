package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.background.domain.Constraints
import io.kmp.taskmanager.background.domain.ExistingPolicy
import io.kmp.taskmanager.background.domain.ScheduleResult
import io.kmp.taskmanager.background.domain.TaskTrigger

/**
 * DEPRECATED in v4.0.0
 *
 * WorkerTypes contained example worker class names that should be in user applications.
 *
 * Migration:
 * Define your own worker identifiers as constants in your app code.
 * ```kotlin
 * // In your app
 * object MyWorkers {
 *     const val SYNC = "SyncWorker"
 *     const val UPLOAD = "UploadWorker"
 * }
 * ```
 */
@Deprecated(
    message = "WorkerTypes removed in v4.0.0. Define worker identifiers in your app code.",
    level = DeprecationLevel.ERROR
)
object WorkerTypes {
    const val HEAVY_PROCESSING_WORKER = "io.kmp.taskmanager.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "io.kmp.taskmanager.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "io.kmp.taskmanager.background.workers.UploadWorker"
}


/**
 * This `expect` class declares that a platform-specific implementation of `BackgroundTaskScheduler`
 * must be provided for each target (Android, iOS).
 */
expect class NativeTaskScheduler : BackgroundTaskScheduler {
    /** Expected function to enqueue a background task. */
    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult

    /** Expected function to cancel a task by ID. */
    override fun cancel(id: String)

    /** Expected function to cancel all scheduled tasks. */
    override fun cancelAll()

    override fun beginWith(task: io.kmp.taskmanager.background.domain.TaskRequest): io.kmp.taskmanager.background.domain.TaskChain

    override fun beginWith(tasks: List<io.kmp.taskmanager.background.domain.TaskRequest>): io.kmp.taskmanager.background.domain.TaskChain

    override fun enqueueChain(chain: io.kmp.taskmanager.background.domain.TaskChain)
}