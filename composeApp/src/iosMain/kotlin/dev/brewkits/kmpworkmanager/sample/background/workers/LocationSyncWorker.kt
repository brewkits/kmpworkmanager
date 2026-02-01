package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay

/**
 * Simulates GPS location data sync with batch uploads
 */
class LocationSyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "LocationSyncWorker started")

        try {
            val locationPoints = 50
            val batchSize = 10

            Logger.i(LogTags.WORKER, "Syncing $locationPoints location points in batches")

            var synced = 0
            while (synced < locationPoints) {
                delay(500) // Simulate network upload

                val batchEnd = minOf(synced + batchSize, locationPoints)
                val batchCount = batchEnd - synced
                synced = batchEnd

                val progress = (synced * 100) / locationPoints
                Logger.i(LogTags.WORKER, "Uploaded batch: $synced/$locationPoints points ($progress%)")
            }

            Logger.i(LogTags.WORKER, "LocationSyncWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "LocationSync",
                    success = true,
                    message = "📍 Synced $locationPoints location points"
                )
            )

            return true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "LocationSyncWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "LocationSync",
                    success = false,
                    message = "❌ Location sync failed: ${e.message}"
                )
            )
            return false
        }
    }
}
