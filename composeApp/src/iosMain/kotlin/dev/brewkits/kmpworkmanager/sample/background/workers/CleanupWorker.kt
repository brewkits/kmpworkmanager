package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay

/**
 * Maintenance task that simulates cache cleanup and space reclamation
 */
class CleanupWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "CleanupWorker started - scanning for old cache files")

        try {
            // Phase 1: Scanning
            Logger.i(LogTags.WORKER, "Scanning cache directories...")
            delay(800)

            val oldFiles = 127 // Simulated file count
            Logger.i(LogTags.WORKER, "Found $oldFiles old cache files to delete")

            // Phase 2: Deleting
            var deleted = 0
            var spaceFreed = 0L

            while (deleted < oldFiles) {
                delay(50)
                deleted++
                spaceFreed += (100..5000).random() // Random file sizes in KB

                if (deleted % 20 == 0 || deleted == oldFiles) {
                    val progress = (deleted * 100) / oldFiles
                    val spaceMB = spaceFreed / 1024
                    Logger.i(LogTags.WORKER, "Cleanup progress: $deleted/$oldFiles files ($progress%), ${spaceMB}MB freed")
                }
            }

            val finalSpaceMB = spaceFreed / 1024

            Logger.i(LogTags.WORKER, "CleanupWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Cleanup",
                    success = true,
                    message = "🧹 Deleted $oldFiles files, freed ${finalSpaceMB}MB"
                )
            )

            return true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "CleanupWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Cleanup",
                    success = false,
                    message = "❌ Cleanup failed: ${e.message}"
                )
            )
            return false
        }
    }
}
