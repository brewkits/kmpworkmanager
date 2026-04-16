package dev.brewkits.kmpworkmanager.sample.background

/**
 * Shared constants for worker identifiers used in the sample app.
 */
object WorkerTypes {
    const val HEAVY_PROCESSING_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingWorker"
    const val SYNC_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.SyncWorker"
    const val UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.UploadWorker"
    const val DATABASE_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.DatabaseWorker"
    const val NETWORK_RETRY_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.NetworkRetryWorker"
    const val IMAGE_PROCESSING_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.ImageProcessingWorker"
    const val LOCATION_SYNC_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.LocationSyncWorker"
    const val CLEANUP_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.CleanupWorker"
    const val BATCH_UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.BatchUploadWorker"
    const val ANALYTICS_WORKER = "dev.brewkits.kmpworkmanager.sample.background.workers.AnalyticsWorker"

    // Built-in workers
    const val HTTP_REQUEST_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker"
    const val HTTP_SYNC_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker"
    const val HTTP_DOWNLOAD_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker"
    const val HTTP_UPLOAD_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker"
    const val FILE_COMPRESSION_WORKER = "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker"
}
