package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.sample.background.workers.*
import dev.brewkits.kmpworkmanager.workers.BuiltinWorkerRegistry

/**
 * A factory for creating IosWorker instances based on their class name.
 */
class IosWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): dev.brewkits.kmpworkmanager.background.data.IosWorker? {
        return when (workerClassName) {
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.SYNC_WORKER -> SyncWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.UPLOAD_WORKER -> UploadWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.HEAVY_PROCESSING_WORKER -> HeavyProcessingWorker()

            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.DATABASE_WORKER -> DatabaseWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.NETWORK_RETRY_WORKER -> NetworkRetryWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.IMAGE_PROCESSING_WORKER -> ImageProcessingWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.LOCATION_SYNC_WORKER -> LocationSyncWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.CLEANUP_WORKER -> CleanupWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.BATCH_UPLOAD_WORKER -> BatchUploadWorker()
            dev.brewkits.kmpworkmanager.sample.background.WorkerTypes.ANALYTICS_WORKER -> AnalyticsWorker()

            else -> {
                // Try builtin workers from BuiltinWorkerRegistry
                val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
                if (builtinWorker != null) {
                    WorkerAdapter(builtinWorker)
                } else {
                    println("KMP_BG_TASK_iOS: Unknown worker class name: $workerClassName")
                    null
                }
            }
        }
    }
}

/**
 * Adapter to wrap library Worker instances as IosWorker for the sample app.
 */
private class WorkerAdapter(private val worker: Worker) : IosWorker {
    override suspend fun doWork(
        input: String?,
        env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
    ): dev.brewkits.kmpworkmanager.background.domain.WorkerResult {
        return worker.doWork(input, env)
    }

    override fun close() {
        worker.close()
    }
}
