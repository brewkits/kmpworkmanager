package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay

/**
 * Uploads multiple files sequentially with per-file and overall progress
 */
class BatchUploadWorker : IosWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        Logger.i(LogTags.WORKER, "BatchUploadWorker started")

        return try {
            val fileNames = listOf("document.pdf", "photo.jpg", "video.mp4", "report.xlsx", "backup.zip")
            val fileSizes = listOf(2, 5, 15, 1, 8) // MB

            Logger.i(LogTags.WORKER, "Uploading ${fileNames.size} files")

            for ((index, fileName) in fileNames.withIndex()) {
                val fileSize = fileSizes[index]
                Logger.i(LogTags.WORKER, "Uploading file ${index + 1}/${fileNames.size}: $fileName (${fileSize}MB)")

                // Simulate upload with chunks
                var uploaded = 0
                while (uploaded < fileSize) {
                    delay(300)
                    uploaded++
                    val fileProgress = (uploaded * 100) / fileSize
                    Logger.i(LogTags.WORKER, "  → $fileName: $uploaded/${fileSize}MB ($fileProgress%)")
                }

                val overallProgress = ((index + 1) * 100) / fileNames.size
                Logger.i(LogTags.WORKER, "Completed $fileName. Overall: ${index + 1}/${fileNames.size} ($overallProgress%)")
            }

            val totalSize = fileSizes.sum()
            Logger.i(LogTags.WORKER, "BatchUploadWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "BatchUpload",
                    success = true,
                    message = "📤 Uploaded ${fileNames.size} files (${totalSize}MB total)"
                )
            )

            WorkerResult.Success(
                message = "Uploaded ${fileNames.size} files (${totalSize}MB total)",
                data = mapOf(
                    "fileCount" to fileNames.size,
                    "totalSizeMB" to totalSize,
                    "files" to fileNames.joinToString(", ")
                )
            )
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "BatchUploadWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "BatchUpload",
                    success = false,
                    message = "❌ Batch upload failed: ${e.message}"
                )
            )
            WorkerResult.Failure("Batch upload failed: ${e.message}")
        }
    }
}
