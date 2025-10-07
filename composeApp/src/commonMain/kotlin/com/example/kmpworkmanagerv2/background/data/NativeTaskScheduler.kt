package com.example.kmpworkmanagerv2.background.data

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.Constraints
import com.example.kmpworkmanagerv2.background.domain.ExistingPolicy
import com.example.kmpworkmanagerv2.background.domain.ScheduleResult
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger

/**
 * Shared constants for worker identifiers to ensure consistency between platforms.
 * The values must be unique strings.
 */
object WorkerTypes {
    const val HEAVY_PROCESSING_WORKER = "com.example.kmpworkmanagerv2.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "com.example.kmpworkmanagerv2.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "com.example.kmpworkmanagerv2.background.workers.UploadWorker"
}

/**
 * This `expect` class declares that a platform-specific implementation of `BackgroundTaskScheduler`
 * must be provided for each target (Android, iOS).
 */
expect class NativeTaskScheduler : BackgroundTaskScheduler {
    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult

    override fun cancel(id: String)

    override fun cancelAll()
}