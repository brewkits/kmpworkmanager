package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.AndroidWorkerFactoryProvider
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger

/**
 * A generic CoroutineWorker that delegates to user-provided AndroidWorker implementations.
 *
 * v2.1.0+: Uses AndroidWorkerFactoryProvider to retrieve factory from WorkerManagerConfig
 * (replaces Koin dependency)
 *
 * This worker acts as the entry point for all deferrable tasks and:
 * - Retrieves the worker class name from input data
 * - Uses the AndroidWorkerFactory to create the worker instance
 * - Delegates execution to the worker's doWork() method
 * - Emits events to TaskEventBus for UI updates
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val workerFactory: AndroidWorkerFactory by lazy {
        AndroidWorkerFactoryProvider.getAndroidWorkerFactory()
    }

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