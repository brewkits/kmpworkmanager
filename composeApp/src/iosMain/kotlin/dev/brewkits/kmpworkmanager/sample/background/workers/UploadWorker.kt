package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UploadWorker : IosWorker {
    override suspend fun doWork(
        input: String?,
        env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
    ): WorkerResult {
        println("=".repeat(60))
        println(" KMP_BG_TASK_iOS: *** UPLOAD WORKER STARTED ***")
        println(" KMP_BG_TASK_iOS: Starting UploadWorker...")
        println(" KMP_BG_TASK_iOS: Input: $input")
        println("=".repeat(60))

        return try {
            // Simulate file upload with progress
            val totalSize = 100
            var uploaded = 0

            println(" KMP_BG_TASK_iOS: 📤 Starting upload of ${totalSize}MB...")

            while (uploaded < totalSize) {
                delay(300)
                uploaded += 10
                val progress = (uploaded * 100) / totalSize
                println(" KMP_BG_TASK_iOS: 📊 Upload progress: $uploaded/$totalSize MB ($progress%)")
            }

            println(" KMP_BG_TASK_iOS: 🎉 UploadWorker finished successfully.")
            println("=".repeat(60))
            println(" KMP_BG_TASK_iOS: *** EMITTING EVENT ***")

            // Emit completion event
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Upload",
                    success = true,
                    message = "📤 Uploaded ${totalSize}MB successfully"
                )
            )

            println(" KMP_BG_TASK_iOS: *** EVENT EMITTED ***")
            println("=".repeat(60))

            WorkerResult.Success(
                message = "Uploaded ${totalSize}MB successfully",
                data = buildJsonObject {
                    put("uploadedSize", totalSize)
                    put("uploadedSizeUnit", "MB")
                }
            )
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: UploadWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Upload",
                    success = false,
                    message = "❌ Upload failed: ${e.message}"
                )
            )
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }
}
