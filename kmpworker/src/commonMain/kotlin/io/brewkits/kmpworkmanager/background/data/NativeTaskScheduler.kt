package io.brewkits.kmpworkmanager.background.data

import io.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import io.brewkits.kmpworkmanager.background.domain.Constraints
import io.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import io.brewkits.kmpworkmanager.background.domain.ScheduleResult
import io.brewkits.kmpworkmanager.background.domain.TaskTrigger

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
    const val HEAVY_PROCESSING_WORKER = "io.brewkits.kmpworkmanager.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "io.brewkits.kmpworkmanager.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "io.brewkits.kmpworkmanager.background.workers.UploadWorker"
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

    override fun beginWith(task: io.brewkits.kmpworkmanager.background.domain.TaskRequest): io.brewkits.kmpworkmanager.background.domain.TaskChain

    override fun beginWith(tasks: List<io.brewkits.kmpworkmanager.background.domain.TaskRequest>): io.brewkits.kmpworkmanager.background.domain.TaskChain

    override fun enqueueChain(chain: io.brewkits.kmpworkmanager.background.domain.TaskChain)
}