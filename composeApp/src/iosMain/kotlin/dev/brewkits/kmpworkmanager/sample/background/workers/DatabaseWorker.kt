package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Simulates database operations with batching and progress updates
 */
class DatabaseWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "DatabaseWorker started")

        try {
            val totalRecords = 1000
            val batchSize = 100
            var processed = 0

            Logger.i(LogTags.WORKER, "Inserting $totalRecords records in batches of $batchSize")

            while (processed < totalRecords) {
                delay(500) // Simulate database insert time

                // Random 10% failure injection
                if (Random.nextFloat() < 0.1f) {
                    throw Exception("Database transaction failed (simulated error)")
                }

                processed += batchSize
                val progress = (processed * 100) / totalRecords
                Logger.i(LogTags.WORKER, "Database progress: $processed/$totalRecords records ($progress%)")
            }

            Logger.i(LogTags.WORKER, "DatabaseWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Database",
                    success = true,
                    message = "💾 Inserted $totalRecords records successfully"
                )
            )

            return true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "DatabaseWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Database",
                    success = false,
                    message = "❌ Database operation failed: ${e.message}"
                )
            )
            return false
        }
    }
}
