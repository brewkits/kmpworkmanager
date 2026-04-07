package dev.brewkits.kmpworkmanager.background.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared registry for active chain jobs on iOS.
 * Allows NativeTaskScheduler to cancel a running chain when REPLACE policy is applied,
 * even across different instances of ChainExecutor or from the Main UI thread.
 */
internal object ChainJobRegistry {
    private val mutex = Mutex()
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * Registers a job for a chainId.
     */
    suspend fun register(chainId: String, job: Job) {
        mutex.withLock {
            activeJobs[chainId] = job
        }
    }

    /**
     * Unregisters a job for a chainId.
     */
    suspend fun unregister(chainId: String, job: Job) {
        mutex.withLock {
            if (activeJobs[chainId] === job) {
                activeJobs.remove(chainId)
            }
        }
    }

    /**
     * Cancels any running job for the given chainId and **suspends until it completes**.
     *
     * `cancelAndJoin()` (not `cancel()`) is required here because the next step —
     * `replaceChainAtomic()` writing a new chain definition to disk — must not overlap
     * with the old job's `finally` block (which may still be flushing progress or writing
     * to the same files). A bare `cancel()` only signals cancellation and returns
     * immediately, leaving a brief window where both jobs write concurrently.
     */
    suspend fun cancel(chainId: String) {
        val job = mutex.withLock {
            activeJobs.remove(chainId)
        }
        job?.cancelAndJoin()
    }
}
