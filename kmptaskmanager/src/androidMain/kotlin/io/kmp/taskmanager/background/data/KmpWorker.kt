package io.kmp.taskmanager.background.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.utils.LogTags
import io.kmp.taskmanager.utils.Logger
import kotlinx.coroutines.delay

/**
 * A generic CoroutineWorker that acts as the entry point for all deferrable tasks.
 * Emits events to TaskEventBus for UI updates.
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()

        return try {
            when (workerClassName) {
                WorkerTypes.SYNC_WORKER -> executeSyncWorker()
                WorkerTypes.UPLOAD_WORKER -> executeUploadWorker()
                "Inexact-Alarm" -> executeInexactAlarm()
                else -> {
                    Logger.e(LogTags.WORKER, "Unknown worker type: $workerClassName")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Worker execution failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Task",
                    success = false,
                    message = "❌ Task failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }

    private suspend fun executeSyncWorker(): Result {
        Logger.i(LogTags.WORKER, "Starting SYNC_WORKER")

        val steps = listOf("Fetching data", "Processing", "Saving")
        for ((index, step) in steps.withIndex()) {
            Logger.d(LogTags.WORKER, "[$step] ${index + 1}/${steps.size}")
            delay(800)
            Logger.d(LogTags.WORKER, "[$step] completed")
        }

        Logger.i(LogTags.WORKER, "SYNC_WORKER finished successfully")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Sync",
                success = true,
                message = "🔄 Data synced successfully"
            )
        )

        return Result.success()
    }

    private suspend fun executeUploadWorker(): Result {
        Logger.i(LogTags.WORKER, "Starting UPLOAD_WORKER")

        val totalSize = 100
        var uploaded = 0

        Logger.i(LogTags.WORKER, "Starting upload of ${totalSize}MB")

        while (uploaded < totalSize) {
            delay(300)
            uploaded += 10
            val progress = (uploaded * 100) / totalSize
            Logger.d(LogTags.WORKER, "Upload progress: $uploaded/$totalSize MB ($progress%)")
        }

        Logger.i(LogTags.WORKER, "UPLOAD_WORKER finished successfully")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Upload",
                success = true,
                message = "📤 Uploaded ${totalSize}MB successfully"
            )
        )

        return Result.success()
    }

    private suspend fun executeInexactAlarm(): Result {
        Logger.i(LogTags.WORKER, "Starting Inexact-Alarm")
        delay(1000)
        Logger.i(LogTags.WORKER, "Inexact-Alarm completed")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Alarm",
                success = true,
                message = "⏰ Alarm triggered successfully"
            )
        )

        return Result.success()
    }
}