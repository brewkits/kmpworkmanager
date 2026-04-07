package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncWorker : IosWorker {
    override suspend fun doWork(
        input: String?,
        env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
    ): WorkerResult {
        println(" KMP_BG_TASK_iOS: Starting SyncWorker...")
        println(" KMP_BG_TASK_iOS: Input: $input")

        return try {
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

            WorkerResult.Success(
                message = "Synced ${steps.size} steps successfully",
                data = buildJsonObject {
                    put("steps", steps.size)
                    put("duration", 2400L)
                }
            )
        } catch (e: Exception) {
            println(" KMP_BG_TASK_iOS: SyncWorker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Sync",
                    success = false,
                    message = "❌ Sync failed: ${e.message}"
                )
            )
            WorkerResult.Failure("Sync failed: ${e.message}")
        }
    }
}
