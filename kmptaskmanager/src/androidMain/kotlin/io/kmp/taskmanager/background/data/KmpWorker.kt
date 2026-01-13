package io.kmp.taskmanager.background.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.kmp.taskmanager.background.domain.AndroidWorkerFactory
import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.utils.LogTags
import io.kmp.taskmanager.utils.Logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A generic CoroutineWorker that delegates to user-provided AndroidWorker implementations.
 *
 * v4.0.0+: Uses AndroidWorkerFactory from Koin instead of hardcoded when() statement
 *
 * This worker acts as the entry point for all deferrable tasks and:
 * - Retrieves the worker class name from input data
 * - Uses the injected AndroidWorkerFactory to create the worker instance
 * - Delegates execution to the worker's doWork() method
 * - Emits events to TaskEventBus for UI updates
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val workerFactory: AndroidWorkerFactory by inject()

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()
        val inputJson = inputData.getString("inputJson")

        Logger.i(LogTags.WORKER, "KmpWorker executing: $workerClassName")

        return try {
            val worker = workerFactory.createWorker(workerClassName)

            if (worker == null) {
                Logger.e(LogTags.WORKER, "Worker factory returned null for: $workerClassName")
                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = workerClassName,
                        success = false,
                        message = "❌ Worker not found: $workerClassName"
                    )
                )
                return Result.failure()
            }

            val success = worker.doWork(inputJson)

            if (success) {
                Logger.i(LogTags.WORKER, "Worker completed successfully: $workerClassName")
                Result.success()
            } else {
                Logger.w(LogTags.WORKER, "Worker returned failure: $workerClassName")
                Result.failure()
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Worker execution failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName,
                    success = false,
                    message = "❌ Failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }
}