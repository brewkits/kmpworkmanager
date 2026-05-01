package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.measureTime

/**
 * Advanced Stress & Performance Tests for iOS File Storage.
 *
 * Each test creates its own IosFileStorage with a unique directory to avoid the
 * shared-field race that occurs when the Kotlin/Native test runner executes tests
 * concurrently across multiple threads.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStorageStressTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmpworkmanager_stress_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    @Test
    fun `Stress Test - Highly concurrent enqueue operations`() = runTest {
        println("🚀 RUNNING STRESS TEST: Highly concurrent enqueue operations")
        val storage = makeStorage("concurrent")
        val coroutineCount = 50
        val itemsPerCoroutine = 10
        val totalExpected = coroutineCount * itemsPerCoroutine

        val duration = measureTime {
            coroutineScope {
                val jobs = (1..coroutineCount).map { coroutineId ->
                    launch(Dispatchers.Default) {
                        for (i in 1..itemsPerCoroutine) {
                            val chainId = "stress-chain-$coroutineId-$i"
                            val steps = listOf(listOf(TaskRequest("StressWorker", "payload-$coroutineId-$i")))

                            storage.saveChainDefinition(chainId, steps)
                            storage.enqueueChain(chainId)
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        println("✅ Stressed $totalExpected concurrent enqueues in $duration")

        try {
            assertEquals(totalExpected, storage.getQueueSize(), "All concurrent enqueues must be persisted without loss")

            val dequeuedSet = mutableSetOf<String>()
            var item: String?
            while (true) {
                item = storage.dequeueChain()
                if (item == null) break
                dequeuedSet.add(item)
                storage.deleteChainDefinition(item)
            }

            assertEquals(totalExpected, dequeuedSet.size, "All items must be exactly retrievable")
            assertEquals(0, storage.getQueueSize(), "Queue must be empty after exhaustive dequeue")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `Boundary Test - Handle zero-length and large payloads gracefully`() = runTest {
        println("🚀 RUNNING BOUNDARY TEST: Zero-length and large payloads")
        val storage = makeStorage("boundary")

        try {
            // 1. Zero-Length / Null payloads (tests v2.3.8 fixes for empty String to NSData)
            val emptyChainId = "empty-chain-id"
            val emptySteps = listOf(listOf(TaskRequest("EmptyWorker", "")))
            storage.saveChainDefinition(emptyChainId, emptySteps)
            storage.enqueueChain(emptyChainId)

            val loadedEmpty = storage.loadChainDefinition(emptyChainId)
            assertNotNull(loadedEmpty)
            assertEquals("", loadedEmpty[0][0].inputJson)

            // 2. Large payload (approx 1MB)
            val largeChainId = "large-chain-id"
            val largeString = "A".repeat(1024 * 1024)
            val largeSteps = listOf(listOf(TaskRequest("LargeWorker", largeString)))
            storage.saveChainDefinition(largeChainId, largeSteps)
            storage.enqueueChain(largeChainId)

            val loadedLarge = storage.loadChainDefinition(largeChainId)
            assertNotNull(loadedLarge)
            assertEquals(1024 * 1024, loadedLarge[0][0].inputJson?.length)
        } finally {
            storage.close()
        }
    }
}
