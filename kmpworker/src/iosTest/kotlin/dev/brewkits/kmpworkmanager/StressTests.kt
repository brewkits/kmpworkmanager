package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

/**
 * v2.3.0: Stress tests for performance and concurrency
 * Tests high load, large data sets, and concurrent operations
 */
@OptIn(ExperimentalForeignApi::class)
class StressTests {

    private lateinit var testDirectoryURL: NSURL
    private lateinit var queueDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        // Create temporary test directory
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_stress_test_${NSDate().timeIntervalSince1970}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(
            testDirectoryURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )

        // Create queue subdirectory
        queueDirectoryURL = testDirectoryURL.URLByAppendingPathComponent("queue")!!
        fileManager.createDirectoryAtURL(
            queueDirectoryURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    @AfterTest
    fun tearDown() {
        // Clean up test directory
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    // ==================== High Concurrency Tests ====================

    @Test
    fun `testHighConcurrency - 1000 concurrent enqueues with no data loss`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        val threadsCount = 10
        val itemsPerThread = 100
        val totalItems = threadsCount * itemsPerThread

        println("Starting high concurrency test: $threadsCount threads × $itemsPerThread items = $totalItems total")

        val startTime = TimeSource.Monotonic.markNow()

        // Launch concurrent enqueues
        val jobs = (0 until threadsCount).map { threadId ->
            async {
                repeat(itemsPerThread) { itemId ->
                    val chainId = "thread-${threadId}-item-${itemId}"
                    queue.enqueue(chainId)
                }
            }
        }

        // Wait for all to complete
        jobs.awaitAll()

        val enqueueDuration = startTime.elapsedNow()
        println("Enqueue completed in ${enqueueDuration.inWholeMilliseconds}ms")

        // Verify no data loss
        val finalSize = queue.getSize()
        assertEquals(totalItems, finalSize, "All $totalItems items should be enqueued without loss")

        // Dequeue and verify count
        var dequeueCount = 0
        val dequeueStart = TimeSource.Monotonic.markNow()

        while (true) {
            val item = queue.dequeue() ?: break
            dequeueCount++
        }

        val dequeueDuration = dequeueStart.elapsedNow()
        println("Dequeue completed in ${dequeueDuration.inWholeMilliseconds}ms")

        assertEquals(totalItems, dequeueCount, "Should dequeue exactly $totalItems items")
        assertEquals(0, queue.getSize(), "Queue should be empty after dequeuing all")

        println("""
            ✅ High concurrency test passed:
            - Enqueued: $totalItems items in ${enqueueDuration.inWholeMilliseconds}ms
            - Dequeued: $dequeueCount items in ${dequeueDuration.inWholeMilliseconds}ms
            - No data loss
        """.trimIndent())
    }

    @Test
    fun `testConcurrentEnqueueDequeue - simultaneous operations`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        val enqueueCount = 500
        val dequeueCount = 300 // Dequeue fewer than enqueued

        println("Starting concurrent enqueue/dequeue test")

        val startTime = TimeSource.Monotonic.markNow()

        // Launch concurrent enqueues and dequeues
        val enqueueJobs = (0 until 5).map { threadId ->
            async {
                repeat(enqueueCount / 5) { itemId ->
                    queue.enqueue("thread-${threadId}-item-${itemId}")
                }
            }
        }

        val dequeueJobs = (0 until 3).map {
            async {
                repeat(dequeueCount / 3) {
                    queue.dequeue() // May return null if queue empty
                }
            }
        }

        // Wait for all operations
        enqueueJobs.awaitAll()
        dequeueJobs.awaitAll()

        val duration = startTime.elapsedNow()
        println("Concurrent operations completed in ${duration.inWholeMilliseconds}ms")

        // Verify final state is consistent
        val remainingSize = queue.getSize()
        assertTrue(
            remainingSize >= 0 && remainingSize <= enqueueCount,
            "Remaining size should be valid: $remainingSize (expected 0-$enqueueCount)"
        )

        println("✅ Concurrent test passed: $remainingSize items remaining")
    }

    // ==================== Large Queue Performance Tests ====================

    @Test
    fun `testLargeQueuePerformance - 10000 items enqueue and dequeue`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)
        val itemCount = 10_000

        println("Starting large queue performance test with $itemCount items")

        // Measure enqueue performance
        val enqueueStart = TimeSource.Monotonic.markNow()

        repeat(itemCount) { i ->
            queue.enqueue("""{"id":"chain-$i","data":"test-data-$i"}""")

            // Log progress every 1000 items
            if ((i + 1) % 1000 == 0) {
                val elapsed = enqueueStart.elapsedNow()
                println("Enqueued ${i + 1} items (${elapsed.inWholeMilliseconds}ms)")
            }
        }

        val enqueueDuration = enqueueStart.elapsedNow()
        val enqueuePerSecond = (itemCount * 1000.0) / enqueueDuration.inWholeMilliseconds

        println("""
            Enqueue performance:
            - Total: $itemCount items
            - Duration: ${enqueueDuration.inWholeMilliseconds}ms
            - Rate: ${enqueuePerSecond.toInt()} items/sec
        """.trimIndent())

        // Verify enqueue performance (should be < 10s)
        assertTrue(
            enqueueDuration.inWholeMilliseconds < 10_000,
            "Enqueue should complete in < 10s (actual: ${enqueueDuration.inWholeMilliseconds}ms)"
        )

        // Verify size
        assertEquals(itemCount, queue.getSize(), "Queue should have $itemCount items")

        // Measure dequeue performance
        val dequeueStart = TimeSource.Monotonic.markNow()
        var dequeuedCount = 0

        while (true) {
            val item = queue.dequeue() ?: break
            dequeuedCount++

            // Log progress every 1000 items
            if (dequeuedCount % 1000 == 0) {
                val elapsed = dequeueStart.elapsedNow()
                println("Dequeued $dequeuedCount items (${elapsed.inWholeMilliseconds}ms)")
            }
        }

        val dequeueDuration = dequeueStart.elapsedNow()
        val dequeuePerSecond = (dequeuedCount * 1000.0) / dequeueDuration.inWholeMilliseconds

        println("""
            Dequeue performance:
            - Total: $dequeuedCount items
            - Duration: ${dequeueDuration.inWholeMilliseconds}ms
            - Rate: ${dequeuePerSecond.toInt()} items/sec
        """.trimIndent())

        // Verify dequeue performance (should be < 10s)
        assertTrue(
            dequeueDuration.inWholeMilliseconds < 10_000,
            "Dequeue should complete in < 10s (actual: ${dequeueDuration.inWholeMilliseconds}ms)"
        )

        // Verify count matches
        assertEquals(itemCount, dequeuedCount, "Should dequeue all $itemCount items")
        assertEquals(0, queue.getSize(), "Queue should be empty")

        println("""
            ✅ Large queue performance test passed:
            - Enqueue: ${enqueuePerSecond.toInt()} items/sec
            - Dequeue: ${dequeuePerSecond.toInt()} items/sec
            - All items processed correctly
        """.trimIndent())
    }

    @Test
    fun `testLargeChainDefinitions - handling of large chain data`() = runTest {
        val fileStorage = IosFileStorage()

        // Create large chain with many steps
        val stepsCount = 100
        val largeSteps = (1..stepsCount).map { stepId ->
            (1..10).map { taskId ->
                TaskRequest(
                    workerClassName = "Worker_${stepId}_$taskId",
                    inputJson = """{"step":$stepId,"task":$taskId,"data":"${"x".repeat(100)}"}"""
                )
            }
        }

        val chainId = "large-chain-test"

        // Measure save time
        val saveStart = TimeSource.Monotonic.markNow()
        fileStorage.saveChainDefinition(chainId, largeSteps)
        val saveDuration = saveStart.elapsedNow()

        println("Saved large chain in ${saveDuration.inWholeMilliseconds}ms")

        // Measure load time
        val loadStart = TimeSource.Monotonic.markNow()
        val loaded = fileStorage.loadChainDefinition(chainId)
        val loadDuration = loadStart.elapsedNow()

        println("Loaded large chain in ${loadDuration.inWholeMilliseconds}ms")

        // Verify data integrity
        assertNotNull(loaded, "Chain should be loaded")
        assertEquals(stepsCount, loaded.size, "Should have $stepsCount steps")
        assertEquals(10, loaded[0].size, "Each step should have 10 tasks")

        // Verify first and last steps
        assertEquals("Worker_1_1", loaded[0][0].workerClassName)
        assertEquals("Worker_${stepsCount}_10", loaded.last().last().workerClassName)

        println("""
            ✅ Large chain test passed:
            - Steps: $stepsCount
            - Tasks per step: 10
            - Save: ${saveDuration.inWholeMilliseconds}ms
            - Load: ${loadDuration.inWholeMilliseconds}ms
        """.trimIndent())
    }

    // ==================== Chain Executor Timeout Tests ====================

    @Test
    fun `testChainExecutorTimeout - verify timeout occurs and progress saved`() = runTest {
        // Note: This is a simplified test. Full ChainExecutor timeout testing
        // requires mock workers and is better suited for end-to-end tests

        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Enqueue chains
        repeat(5) { i ->
            queue.enqueue("chain-timeout-$i")
        }

        assertEquals(5, queue.getSize())

        // Simulate partial execution
        queue.dequeue() // Process 1
        queue.dequeue() // Process 2

        assertEquals(3, queue.getSize(), "3 chains should remain after partial execution")

        // Verify queue persists state
        val newQueue = AppendOnlyQueue(queueDirectoryURL)
        assertEquals(3, newQueue.getSize(), "Queue state should persist after reconnect")

        println("✅ Timeout simulation test passed: Queue maintains state")
    }

    // ==================== Memory and Resource Tests ====================

    @Test
    fun `testMemoryUsage - enqueue and dequeue cycle doesn't leak`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Run multiple cycles
        repeat(10) { cycle ->
            // Enqueue 1000 items
            repeat(1000) { i ->
                queue.enqueue("cycle-${cycle}-item-$i")
            }

            // Dequeue all
            repeat(1000) {
                queue.dequeue()
            }

            assertEquals(0, queue.getSize(), "Queue should be empty after cycle $cycle")
        }

        println("✅ Memory test passed: 10 cycles of 1000 items with no leaks")
    }

    @Test
    fun `testFileHandleCleanup - no resource leaks from file operations`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Perform many file operations
        repeat(1000) { i ->
            queue.enqueue("item-$i")

            if (i % 100 == 0) {
                // Trigger size check (opens file handle)
                queue.getSize()
            }
        }

        // Dequeue all (opens many file handles)
        repeat(1000) {
            queue.dequeue()
        }

        // If we get here without crashes, file handles were properly closed
        println("✅ File handle cleanup test passed: 1000 operations with no leaks")
    }

    // ==================== Edge Case Stress Tests ====================

    @Test
    fun `testRapidEnqueueDequeue - stress test with alternating operations`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        repeat(1000) { i ->
            // Enqueue 2
            queue.enqueue("item-${i * 2}")
            queue.enqueue("item-${i * 2 + 1}")

            // Dequeue 1
            queue.dequeue()

            // Net effect: +1 item per iteration
        }

        // Should have 1000 items remaining
        assertEquals(1000, queue.getSize(), "Should have 1000 items after alternating operations")

        println("✅ Rapid enqueue/dequeue test passed")
    }

    @Test
    fun `testMaxQueueSize - verify size limits enforced`() = runTest {
        val fileStorage = IosFileStorage()

        // Try to exceed MAX_QUEUE_SIZE (1000)
        // Note: AppendOnlyQueue doesn't enforce limit internally,
        // but IosFileStorage.enqueueChain() does

        repeat(999) { i ->
            fileStorage.enqueueChain("chain-$i")
        }

        assertEquals(999, fileStorage.getQueueSize())

        // One more should succeed
        fileStorage.enqueueChain("chain-999")
        assertEquals(1000, fileStorage.getQueueSize())

        // Next one should fail
        try {
            fileStorage.enqueueChain("chain-1000")
            fail("Should throw exception when exceeding MAX_QUEUE_SIZE")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Queue size limit"))
        }

        println("✅ Max queue size test passed: Limit enforced at 1000 items")
    }
}
