package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay

/**
 * Background analytics sync that batches events and compresses payload
 */
class AnalyticsWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "AnalyticsWorker started")

        try {
            // Phase 1: Collect pending events
            Logger.i(LogTags.WORKER, "Collecting pending analytics events...")
            delay(500)

            val eventCount = 243 // Simulated event count
            Logger.i(LogTags.WORKER, "Found $eventCount pending events")

            // Phase 2: Batch events
            Logger.i(LogTags.WORKER, "Batching events...")
            delay(600)
            val batchCount = (eventCount + 49) / 50 // Round up to nearest 50
            Logger.i(LogTags.WORKER, "Created $batchCount batches")

            // Phase 3: Compress
            Logger.i(LogTags.WORKER, "Compressing payload...")
            delay(700)
            val originalSize = eventCount * 2 // KB
            val compressedSize = (originalSize * 0.3).toInt()
            Logger.i(LogTags.WORKER, "Compressed ${originalSize}KB → ${compressedSize}KB")

            // Phase 4: Upload
            Logger.i(LogTags.WORKER, "Uploading to analytics server...")
            delay(1000)
            Logger.i(LogTags.WORKER, "Upload complete")

            Logger.i(LogTags.WORKER, "AnalyticsWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Analytics",
                    success = true,
                    message = "📊 Synced $eventCount events (${compressedSize}KB)"
                )
            )

            return true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "AnalyticsWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Analytics",
                    success = false,
                    message = "❌ Analytics sync failed: ${e.message}"
                )
            )
            return false
        }
    }
}
