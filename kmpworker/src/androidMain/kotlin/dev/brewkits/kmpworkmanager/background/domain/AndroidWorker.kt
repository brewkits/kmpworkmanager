package dev.brewkits.kmpworkmanager.background.domain

/**
 * Android-specific worker interface.
 */
interface AndroidWorker : Worker {
    /**
     * Performs the background work.
     *
     * @param input Optional input data passed from scheduler.enqueue()
     * @param env Environment providing progress reporting and cancellation checks
     * @return WorkerResult indicating success/failure
     */
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult
}
