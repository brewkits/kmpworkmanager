package io.brewkits.kmpworkmanager.sample.background.data

import io.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingWorker
import io.brewkits.kmpworkmanager.sample.background.workers.SyncWorker
import io.brewkits.kmpworkmanager.sample.background.workers.UploadWorker

/**
 * A factory for creating IosWorker instances based on their class name.
 */
class IosWorkerFactory {
    fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            WorkerTypes.SYNC_WORKER -> SyncWorker()
            WorkerTypes.UPLOAD_WORKER -> UploadWorker()
            WorkerTypes.HEAVY_PROCESSING_WORKER -> HeavyProcessingWorker()
            else -> {
                println(" KMP_BG_TASK_iOS: Unknown worker class name: $workerClassName")
                null
            }
        }
    }
}
