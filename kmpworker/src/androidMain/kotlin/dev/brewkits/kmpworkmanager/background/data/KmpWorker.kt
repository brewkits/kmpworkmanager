package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Standard implementation of [BaseKmpWorker] for non-heavy background tasks.
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    workerFactory: AndroidWorkerFactory
) : BaseKmpWorker(appContext, workerParams, workerFactory) {

    @Deprecated(
        "Use the constructor that accepts a workerFactory parameter for proper DI support.",
        level = DeprecationLevel.WARNING
    )
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        KmpWorkManagerKoin.getKoin().get()
    )

    override val workerLogTag: String get() = "KmpWorker"

    override suspend fun doWork(): Result = doWorkInternal()

    override suspend fun performWork(workerClassName: String, inputJson: String?): WorkerResult {
        val worker = workerFactory.createWorker(workerClassName)
            ?: run {
                Logger.e(LogTags.WORKER, "Worker not found: $workerClassName — not registered in factory")
                return WorkerResult.Failure("Worker not found: $workerClassName")
            }

        val env = WorkerEnvironment(
            progressListener = object : ProgressListener {
                override fun onProgressUpdate(progress: WorkerProgress) {
                    setProgressAsync(androidx.work.Data.Builder().putInt("progress", progress.progress).build())
                }
            },
            isCancelled = { isStopped }
        )

        return worker.doWork(inputJson, env)
    }
}
