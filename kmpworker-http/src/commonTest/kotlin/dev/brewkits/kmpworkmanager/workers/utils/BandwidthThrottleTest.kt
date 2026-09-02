@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brewkits.kmpworkmanager.workers.utils

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [BandwidthThrottle] — pure algorithm coverage using `runTest`'s virtual clock
 * (via `TestScope.currentTime`) as the injectable [BandwidthThrottle.nowMs], so every
 * assertion about delay amounts is exact and the test suite doesn't actually sleep.
 */
class BandwidthThrottleTest {

    @Test
    fun `rejects a non-positive maxBytesPerSecond`() {
        assertFailsWith<IllegalArgumentException> { BandwidthThrottle(0) }
        assertFailsWith<IllegalArgumentException> { BandwidthThrottle(-1) }
    }

    @Test
    fun `consume with zero or negative bytes is a no-op`() = runTest {
        val throttle = BandwidthThrottle(1000, nowMs = { currentTime })
        throttle.consume(0)
        throttle.consume(-5)
        assertEquals(0L, currentTime, "No delay should have been scheduled")
    }

    @Test
    fun `a burst exactly at the cap consumed instantly requires a one-second delay`() = runTest {
        val throttle = BandwidthThrottle(maxBytesPerSecond = 1000, nowMs = { currentTime })
        throttle.consume(1000) // 1000 bytes in 0ms elapsed vs. a 1000 B/s cap → 1s "debt"
        assertEquals(1000L, currentTime, "Consuming a full second's budget instantly must delay ~1s")
    }

    @Test
    fun `consuming under the cap over real elapsed time needs no delay`() = runTest {
        val throttle = BandwidthThrottle(maxBytesPerSecond = 1000, nowMs = { currentTime })
        // Simulate 2 seconds having already passed (e.g. a slow network) before consuming
        // bytes that fit well within what 2s at 1000 B/s would allow.
        kotlinx.coroutines.delay(2000)
        throttle.consume(500)
        assertEquals(2000L, currentTime, "500 bytes fits inside a 2000ms budget — no extra delay")
    }

    @Test
    fun `accounting accumulates correctly across multiple consume calls`() = runTest {
        val throttle = BandwidthThrottle(maxBytesPerSecond = 1000, nowMs = { currentTime })
        throttle.consume(1000) // → delay to t=1000ms
        throttle.consume(1000) // another full second's worth, instantly → delay to t=2000ms
        assertEquals(
            2000L, currentTime,
            "Two full-second bursts back-to-back must total ~2s of delay, not reset per call"
        )
    }

    @Test
    fun `a shared throttle correctly bounds aggregate rate across concurrent consumers`() = runTest {
        // This is the exact scenario ParallelHttpDownloadWorker/ParallelHttpUploadWorker rely
        // on: N coroutines sharing ONE throttle instance must not be able to each independently
        // get a full maxBytesPerSecond budget (which would allow N * rate in aggregate).
        val throttle = BandwidthThrottle(maxBytesPerSecond = 1000, nowMs = { currentTime })
        val chunks = 4

        val jobs = List(chunks) {
            launch { throttle.consume(1000) } // each chunk instantly "sends" a full second's budget
        }
        jobs.forEach { it.join() }

        // Aggregate consumed = 4000 bytes at a 1000 B/s cap → must take ~4s total, proving the
        // budget was shared (not 1s, which is what 4 independent per-chunk throttles would give).
        assertEquals(4000L, currentTime, "Aggregate 4000 bytes at 1000 B/s cap must take ~4s, proving one shared budget")
    }

    @Test
    fun `default nowMs uses a real clock and does not throw`() = runTest {
        // Smoke test for the production default (real currentTimeMillis()) — everything else
        // in this file overrides nowMs with virtual time for exact assertions.
        val throttle = BandwidthThrottle(maxBytesPerSecond = 10_000_000_000L)
        throttle.consume(1) // negligible vs. a huge cap — must return immediately, no crash
        assertTrue(true)
    }
}
