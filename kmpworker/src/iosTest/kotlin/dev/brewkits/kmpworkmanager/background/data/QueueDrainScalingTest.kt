@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Measures how the cost of draining [AppendOnlyQueue] grows with queue depth.
 *
 * The concern: `dequeue()` calls `shouldCompact()` on every successful pop, and
 * `shouldCompact()` calls `countTotalLines()`, which walks the whole queue file record by
 * record. If nothing caches that count, popping N items costs O(N²) file traversal — and the
 * documented ceiling is `MAX_QUEUE_SIZE = 10_000`.
 *
 * The assertion is a growth ratio rather than a wall-clock number, because absolute timings
 * on a simulator under CI load mean nothing. Quadratic growth shows up as ~16x for 4x the
 * data; linear-ish growth stays near 4x. The threshold sits between the two, well clear of
 * both, so this fails on a complexity regression rather than on a slow machine.
 */
class QueueDrainScalingTest {

    private lateinit var root: NSURL

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        root = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}QueueDrainScaling-$stamp-${platform.posix.rand()}"
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(root, error = null)
    }

    private fun newQueue(tag: String): AppendOnlyQueue {
        val dir = root.URLByAppendingPathComponent(tag)!!
        NSFileManager.defaultManager.createDirectoryAtURL(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        return AppendOnlyQueue(baseDirectoryURL = dir, isTestMode = true)
    }

    /** Fills a queue with [n] items and returns how long draining all of them takes, in µs. */
    private suspend fun drainCost(tag: String, n: Int): Long {
        val queue = newQueue(tag)
        repeat(n) { queue.enqueue("""{"id":"task-$it","payload":"x"}""") }

        val mark = TimeSource.Monotonic.markNow()
        var popped = 0
        while (true) {
            queue.dequeue() ?: break
            popped++
        }
        val elapsed = mark.elapsedNow().inWholeMicroseconds.coerceAtLeast(1)
        assertEquals(n, popped, "drain must return every item it was given ($tag)")
        return elapsed
    }

    @Test
    fun drainingTheQueueDoesNotCostQuadraticTime() = runTest {
        withContext(Dispatchers.Default) {
            // Warm-up: first touch pays one-off costs (format detection, file creation) that
            // would otherwise be charged entirely to the smaller sample.
            drainCost("warmup", 100)

            val small = drainCost("small", 250)
            val large = drainCost("large", 1000)

            val ratio = large.toDouble() / small.toDouble()
            println("QUEUE_DRAIN_SCALING small(250)=${small}us large(1000)=${large}us ratio=$ratio")

            assertTrue(
                ratio < 8.0,
                "Draining 4x the items cost ${ratio}x the time (small=${small}us, " +
                    "large=${large}us). Linear would be ~4x; quadratic ~16x. A ratio this " +
                    "high means per-dequeue work grows with queue depth — check whether " +
                    "shouldCompact()/countTotalLines() is rescanning the whole file on " +
                    "every pop."
            )
        }
    }
}
