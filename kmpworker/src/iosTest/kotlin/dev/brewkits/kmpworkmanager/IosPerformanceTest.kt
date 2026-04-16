@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import platform.Foundation.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.measureTime

/**
 * Performance benchmarks for iOS storage and queue operations.
 */
class IosPerformanceTest {

    private val fileManager = NSFileManager.defaultManager

    private fun freshTempUrl(): NSURL {
        val tmpDir = NSTemporaryDirectory()
        val tmp = NSURL.fileURLWithPath(tmpDir)
        val url = tmp.URLByAppendingPathComponent("kmptest_perf_${NSDate().timeIntervalSince1970}", isDirectory = true)!!
        fileManager.removeItemAtURL(url, error = null)
        return url
    }

    @Test
    fun benchmark_metadata_write_latency() = runTest {
        val baseUrl = freshTempUrl()
        val storage = IosFileStorage(IosFileStorageConfig(0L), baseUrl)
        
        val count = 100
        val duration = measureTime {
            for (i in 1..count) {
                storage.saveTaskMetadata("task-$i", mapOf("worker" to "Worker$i"), periodic = false)
            }
        }
        
        val avgMs = duration.inWholeMilliseconds.toDouble() / count
        println("Performance: Average metadata write latency: ${avgMs}ms")
        
        // Target: < 10ms per write on modern simulator/hardware
        assertTrue(avgMs < 50.0, "Metadata write too slow: ${avgMs}ms")
        
        storage.close()
        fileManager.removeItemAtURL(baseUrl, error = null)
    }

    @Test
    fun benchmark_queue_enqueue_latency() = runTest {
        val baseUrl = freshTempUrl()
        val storage = IosFileStorage(IosFileStorageConfig(0L), baseUrl)
        
        val count = 50
        val duration = measureTime {
            for (i in 1..count) {
                storage.enqueueChain("chain-$i")
            }
        }
        
        val avgMs = duration.inWholeMilliseconds.toDouble() / count
        println("Performance: Average queue enqueue latency: ${avgMs}ms")
        
        storage.close()
        fileManager.removeItemAtURL(baseUrl, error = null)
    }
}
