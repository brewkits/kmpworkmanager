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
 * Designed to achieve higher coverage on edge cases:
 * - High-concurrency rapid enqueueing/dequeueing.
 * - Queue resilience under heavy load.
 * - Empty string/payload edge cases serialization tests.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStorageStressTest {

    private lateinit var fileStorage: IosFileStorage
    private lateinit var testDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_stress_test_${NSDate().timeIntervalSince1970()}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(testDirectoryURL, withIntermediateDirectories = true, attributes = null, error = null)

        fileStorage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = testDirectoryURL
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        fileStorage.close()
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    @Test
    fun `Stress Test - Highly concurrent enqueue operations`() = runTest {
        println("🚀 RUNNING STRESS TEST: Highly concurrent enqueue operations")
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
                            
                            // Rapid I/O: Define -> Enqueue
                            fileStorage.saveChainDefinition(chainId, steps)
                            fileStorage.enqueueChain(chainId)
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        println("✅ Stressed $totalExpected concurrent enqueues in $duration")
        assertEquals(totalExpected, fileStorage.getQueueSize(), "All concurrent enqueues must be persisted without loss")

        // Validation - ensure we can read back all of them
        val dequeuedSet = mutableSetOf<String>()
        var item: String?
        while (true) {
            item = fileStorage.dequeueChain()
            if (item == null) break
            dequeuedSet.add(item)
            fileStorage.deleteChainDefinition(item)
        }

        assertEquals(totalExpected, dequeuedSet.size, "All items must be exactly retrievable")
        assertEquals(0, fileStorage.getQueueSize(), "Queue must be empty after exhaustive dequeue")
    }

    @Test
    fun `Boundary Test - Handle zero-length and large payloads gracefully`() = runTest {
        println("🚀 RUNNING BOUNDARY TEST: Zero-length and large payloads")

        // 1. Zero-Length / Null payloads (tests v2.3.8 fixes for empty String to NSData)
        val emptyChainId = "empty-chain-id"
        val emptySteps = listOf(listOf(TaskRequest("EmptyWorker", "")))
        fileStorage.saveChainDefinition(emptyChainId, emptySteps)
        fileStorage.enqueueChain(emptyChainId)

        val loadedEmpty = fileStorage.loadChainDefinition(emptyChainId)
        assertNotNull(loadedEmpty)
        assertEquals("", loadedEmpty[0][0].inputJson)

        // 2. Large payload (approx 1MB)
        val largeChainId = "large-chain-id"
        val largeString = "A".repeat(1024 * 1024)
        val largeSteps = listOf(listOf(TaskRequest("LargeWorker", largeString)))
        fileStorage.saveChainDefinition(largeChainId, largeSteps)
        fileStorage.enqueueChain(largeChainId)

        val loadedLarge = fileStorage.loadChainDefinition(largeChainId)
        assertNotNull(loadedLarge)
        assertEquals(1024 * 1024, loadedLarge[0][0].inputJson?.length)
    }
}
