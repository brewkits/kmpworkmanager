package dev.brewkits.kmpworkmanager.sample.background.data

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/**
 * A simple interface for all background workers on the iOS platform.
 *
 * v2.3.0+: Changed return type from Boolean to WorkerResult
 */
interface IosWorker : dev.brewkits.kmpworkmanager.background.domain.Worker {
    /**
     * Performs the background work.
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @param env Environment providing progress reporting and cancellation checks
     * @return WorkerResult indicating success/failure with optional data and message
     */
    override suspend fun doWork(
        input: String?,
        env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
    ): dev.brewkits.kmpworkmanager.background.domain.WorkerResult

    /**
     * Called immediately after [doWork] returns.
     */
    override fun close() {}
}
