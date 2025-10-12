package com.example.kmpworkmanagerv2.background.data

/**
 * A simple interface for all background workers on the iOS platform.
 */
interface IosWorker {
    /**
     * The main work to be performed by the worker.
     * @param input Optional input data for the worker.
     * @return `true` if the work succeeded, `false` otherwise.
     */
    suspend fun doWork(input: String?): Boolean
}
