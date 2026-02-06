package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.sample.background.workers.*
import dev.brewkits.kmpworkmanager.workers.BuiltinWorkerRegistry

/**
 * A factory for creating IosWorker instances based on their class name.
 */
class IosWorkerFactory {
    fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            // Original workers
            WorkerTypes.SYNC_WORKER -> SyncWorker()
            WorkerTypes.UPLOAD_WORKER -> UploadWorker()
            WorkerTypes.HEAVY_PROCESSING_WORKER -> HeavyProcessingWorker()

            // New workers - Phase 2
            WorkerTypes.DATABASE_WORKER -> DatabaseWorker()
            WorkerTypes.NETWORK_RETRY_WORKER -> NetworkRetryWorker()
            WorkerTypes.IMAGE_PROCESSING_WORKER -> ImageProcessingWorker()
            WorkerTypes.LOCATION_SYNC_WORKER -> LocationSyncWorker()
            WorkerTypes.CLEANUP_WORKER -> CleanupWorker()
            WorkerTypes.BATCH_UPLOAD_WORKER -> BatchUploadWorker()
            WorkerTypes.ANALYTICS_WORKER -> AnalyticsWorker()

            else -> {
                // Try builtin workers from BuiltinWorkerRegistry
                val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
                if (builtinWorker != null) {
                    WorkerAdapter(builtinWorker)
                } else {
                    println(" KMP_BG_TASK_iOS: Unknown worker class name: $workerClassName")
                    null
                }
            }
        }
    }
}

/**
 * Adapter to wrap library Worker instances as IosWorker for the sample app.
 *
 * v2.3.0+: Updated to pass through WorkerResult directly
 */
private class WorkerAdapter(private val worker: Worker) : IosWorker {
    override suspend fun doWork(input: String?) = worker.doWork(input)
}
