package io.brewkits.kmpworkmanager.sample.background.data

import io.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import io.brewkits.kmpworkmanager.sample.background.domain.Constraints
import io.brewkits.kmpworkmanager.sample.background.domain.ExistingPolicy
import io.brewkits.kmpworkmanager.sample.background.domain.ScheduleResult
import io.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger

/**
 * Shared constants for worker identifiers to ensure consistency between platforms.
 * These unique strings are used to map a task ID to the actual worker/job class on each platform.
 */
object WorkerTypes {
    const val HEAVY_PROCESSING_WORKER = "io.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "io.brewkits.kmpworkmanager.sample.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "io.brewkits.kmpworkmanager.sample.background.workers.UploadWorker"
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

    override fun beginWith(task: io.brewkits.kmpworkmanager.sample.background.domain.TaskRequest): io.brewkits.kmpworkmanager.sample.background.domain.TaskChain

    override fun beginWith(tasks: List<io.brewkits.kmpworkmanager.sample.background.domain.TaskRequest>): io.brewkits.kmpworkmanager.sample.background.domain.TaskChain

    override fun enqueueChain(chain: io.brewkits.kmpworkmanager.sample.background.domain.TaskChain)
}