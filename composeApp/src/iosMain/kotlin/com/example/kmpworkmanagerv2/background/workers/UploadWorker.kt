package com.example.kmpworkmanagerv2.background.workers

import com.example.kmpworkmanagerv2.background.data.IosWorker
import com.example.kmpworkmanagerv2.background.domain.TaskCompletionEvent
import com.example.kmpworkmanagerv2.background.domain.TaskEventBus
import kotlinx.coroutines.delay

class UploadWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        println("=".repeat(60))
        println(" KMP_BG_TASK_iOS: *** UPLOAD WORKER STARTED ***")
        println(" KMP_BG_TASK_iOS: Starting UploadWorker...")
        println(" KMP_BG_TASK_iOS: Input: $input")
        println("=".repeat(60))

        try {
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

            return true
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: UploadWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Upload",
                    success = false,
                    message = "❌ Upload failed: ${e.message}"
                )
            )
            return false
        }
    }
}
