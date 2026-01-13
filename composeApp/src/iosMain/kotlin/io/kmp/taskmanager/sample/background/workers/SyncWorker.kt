package io.kmp.taskmanager.sample.background.workers

import io.kmp.taskmanager.sample.background.data.IosWorker
import io.kmp.taskmanager.sample.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.sample.background.domain.TaskEventBus
import kotlinx.coroutines.delay

class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        println(" KMP_BG_TASK_iOS: Starting SyncWorker...")
        println(" KMP_BG_TASK_iOS: Input: $input")

        try {
            // Simulate network sync with multiple steps
            val steps = listOf("Fetching data", "Processing", "Saving")
            for ((index, step) in steps.withIndex()) {
                println(" KMP_BG_TASK_iOS: 📊 [$step] ${index + 1}/${steps.size}")
                delay(800)
                println(" KMP_BG_TASK_iOS: ✓ [$step] completed")
            }

            println(" KMP_BG_TASK_iOS: 🎉 SyncWorker finished successfully.")

            // Emit completion event
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Sync",
                    success = true,
                    message = "🔄 Data synced successfully"
                )
            )

            return true
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: SyncWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Sync",
                    success = false,
                    message = "❌ Sync failed: ${e.message}"
                )
            )
            return false
        }
    }
}
