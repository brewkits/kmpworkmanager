package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/**
 * A simple interface for all background workers on the iOS platform.
 *
 * v2.3.0+: Changed return type from Boolean to WorkerResult
 */
interface IosWorker {
    /**
     * The main work to be performed by the worker.
     * @param input Optional input data for the worker.
     * @return WorkerResult indicating success/failure with optional data
     */
    suspend fun doWork(input: String?): WorkerResult
}
