package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.Constraints
import dev.brewkits.kmpworkmanager.sample.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.sample.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskTrigger

/**
 * Shared constants for worker identifiers to ensure consistency between platforms.
 * These unique strings are used to map a task ID to the actual worker/job class on each platform.
 */
object WorkerTypes {
    // Original workers
    const val HEAVY_PROCESSING_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.UploadWorker"

    // New workers - Phase 2
    const val DATABASE_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.DatabaseWorker"
    const val NETWORK_RETRY_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.NetworkRetryWorker"
    const val IMAGE_PROCESSING_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.ImageProcessingWorker"
    const val LOCATION_SYNC_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.LocationSyncWorker"
    const val CLEANUP_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.CleanupWorker"
    const val BATCH_UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.BatchUploadWorker"
    const val ANALYTICS_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.AnalyticsWorker"

    // Built-in workers
    const val HTTP_REQUEST_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker"
    const val HTTP_SYNC_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker"
    const val HTTP_DOWNLOAD_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker"
    const val HTTP_UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker"
    const val FILE_COMPRESSION_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
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

    override fun beginWith(task: dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest): dev.brewkits.kmpworkmanager.sample.background.domain.TaskChain

    override fun beginWith(tasks: List<dev.brewkits.kmpworkmanager.sample.background.domain.TaskRequest>): dev.brewkits.kmpworkmanager.sample.background.domain.TaskChain

    override fun enqueueChain(
        chain: dev.brewkits.kmpworkmanager.sample.background.domain.TaskChain,
        id: String?,
        policy: ExistingPolicy
    )
}