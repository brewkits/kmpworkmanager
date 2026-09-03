package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.utils.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token-bucket rate limiter for streamed transfer workers (download/upload).
 *
 * Tracks total bytes consumed since construction and, on each [consume] call, sleeps just
 * long enough that the AVERAGE rate since construction does not exceed [maxBytesPerSecond].
 * This is deliberately an average-rate limiter, not a hard per-chunk cap: a burst that
 * completes faster than the target rate is allowed to run ahead momentarily (up to however
 * far ahead the caller's own chunk size lets it get) and then "pays it back" with a single
 * proportional delay, rather than being artificially chopped into fixed time slices.
 *
 * **Thread-safety**: [consume] is safe to call concurrently from multiple coroutines sharing
 * one instance — the accounting update happens under a [Mutex], while the actual [delay]
 * happens *outside* the lock so concurrent callers each wait their own computed delay rather
 * than serializing on each other's sleep. This lets [ParallelHttpDownloadWorker]/
 * [ParallelHttpUploadWorker] share ONE throttle across all their concurrent chunks/files —
 * using a separate throttle per chunk would allow `numChunks * maxBytesPerSecond` in
 * aggregate, defeating the whole point of the limit.
 */
internal class BandwidthThrottle(
    private val maxBytesPerSecond: Long,
    private val nowMs: () -> Long = { currentTimeMillis() }
) {
    init {
        require(maxBytesPerSecond > 0) {
            "maxBytesPerSecond must be positive, got $maxBytesPerSecond"
        }
    }

    private val startTimeMs = nowMs()
    private val mutex = Mutex()
    private var totalBytesConsumed = 0L

    /**
     * Call after transferring [bytes] more of the stream. Suspends just long enough to keep
     * the average rate since construction at or below [maxBytesPerSecond]. No-op for
     * `bytes <= 0`.
     */
    suspend fun consume(bytes: Int) {
        if (bytes <= 0) return
        val delayMs = mutex.withLock {
            totalBytesConsumed += bytes
            // How long the transfer SHOULD have taken so far, at the target rate, to move
            // this many bytes — compared against how long it actually took. If we're ahead
            // of schedule, sleep the difference.
            val expectedElapsedMs = (totalBytesConsumed * 1000L) / maxBytesPerSecond
            val actualElapsedMs = nowMs() - startTimeMs
            expectedElapsedMs - actualElapsedMs
        }
        if (delayMs > 0) {
            delay(delayMs)
        }
    }
}
