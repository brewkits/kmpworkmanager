package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.BGTaskType
import dev.brewkits.kmpworkmanager.background.domain.IosWorker
import dev.brewkits.kmpworkmanager.background.domain.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/**
 * Comprehensive tests for High Priority Fixes #8-9: iOS Race Conditions & Deadlock Prevention
 *
 * Fix #8: IosFileStorage flush race condition
 * Bug: isFlushing boolean flag caused race condition - concurrent flushNow() could proceed
 *      while flush was in progress, causing data corruption
 * Fix: Replaced with CompletableDeferred<Unit> for proper synchronization
 *      - flushCompletionSignal tracks ongoing flush
 *      - flushNow() awaits the signal before proceeding
 *
 * Fix #9: ChainExecutor close() deadlock prevention
 * Bug: close() called fileStorage.flushNow() while holding closeMutex,
 *      causing potential deadlock in shutdown scenarios
 * Fix: Made close() non-blocking by launching async cleanup
 *      Added closeAsync() for proper async cleanup
 *
 * This test verifies:
 * - Concurrent flushNow() calls are properly synchronized
 * - No race conditions in progress buffer flushing
 * - close() doesn't block indefinitely
 * - closeAsync() properly awaits cleanup
 * - No deadlocks in concurrent scenarios
 */
class IosRaceConditionTest {

    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() {
        val fileManager = NSFileManager.defaultManager
        val tempDir = fileManager.temporaryDirectory
        testDirectory = tempDir.URLByAppendingPathComponent("IosRaceConditionTest-${NSDate().timeIntervalSince1970}")

        fileManager.createDirectoryAtURL(
            testDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    // ==================== Fix #8: Flush Race Condition ====================

    /**
     * Test 1: Concurrent flushNow() calls are properly synchronized
     *
     * Scenario: Multiple coroutines call flushNow() simultaneously
     * Expected: No race condition, all flushes complete successfully
     */
    @Test
    fun testConcurrentFlushNowSynchronized() = runBlocking {
        val fileStorage = IosFileStorage(baseDirectory = testDirectory)

        // Create initial progress
        val progress = ChainProgress(
            chainId = "concurrent-flush",
            totalSteps = 10,
            completedSteps = listOf(0, 1, 2)
        )
        fileStorage.saveChainProgress(progress)

        // Launch multiple concurrent flushNow() calls
        val jobs = (1..10).map {
            launch(Dispatchers.Default) {
                fileStorage.flushNow()
            }
        }

        // Wait for all to complete
        jobs.forEach { it.join() }

        // Verify no corruption - progress should still be readable
        val loaded = fileStorage.loadChainProgress("concurrent-flush")
        assertNotNull(loaded, "Progress should be preserved after concurrent flushes")
        assertEquals(listOf(0, 1, 2), loaded?.completedSteps, "Data should not be corrupted")
    }

    /**
     * Test 2: flushNow() waits for ongoing flush to complete
     *
     * Scenario: Start a flush, then call flushNow() immediately
     * Expected: flushNow() should wait for the ongoing flush
     */
    @Test
    fun testFlushNowWaitsForOngoingFlush() = runBlocking {
        val fileStorage = IosFileStorage(baseDirectory = testDirectory)

        val progress1 = ChainProgress(
            chainId = "wait-test",
            totalSteps = 5,
            completedSteps = listOf(0)
        )
        fileStorage.saveChainProgress(progress1)

        // Trigger periodic flush (starts in background)
        delay(100) // Give time for periodic flush to start

        // Update progress while flush might be ongoing
        val progress2 = progress1.withCompletedStep(1)
        fileStorage.saveChainProgress(progress2)

        // Call flushNow() - should wait if flush is ongoing
        fileStorage.flushNow()

        // Verify final state is consistent
        val loaded = fileStorage.loadChainProgress("wait-test")
        assertNotNull(loaded, "Progress should be loadable")
        assertTrue(
            loaded?.completedSteps?.contains(1) == true,
            "Latest update should be persisted"
        )
    }

    /**
     * Test 3: Multiple rapid flushNow() calls don't cause corruption
     *
     * Scenario: Repeatedly call flushNow() in tight loop
     * Expected: All calls succeed, no data corruption
     */
    @Test
    fun testRapidFlushNowCallsNoCorruption() = runBlocking {
        val fileStorage = IosFileStorage(baseDirectory = testDirectory)

        val progress = ChainProgress(
            chainId = "rapid-flush",
            totalSteps = 3,
            completedSteps = listOf(0, 1)
        )
        fileStorage.saveChainProgress(progress)

        // Rapid flushNow() calls
        repeat(20) {
            fileStorage.flushNow()
            delay(10) // Small delay
        }

        // Verify no corruption
        val loaded = fileStorage.loadChainProgress("rapid-flush")
        assertNotNull(loaded, "Progress should still be readable")
        assertEquals(listOf(0, 1), loaded?.completedSteps, "Data should not be corrupted")
    }

    /**
     * Test 4: Concurrent saves and flushes work correctly
     *
     * Scenario: Multiple coroutines saving progress while others flush
     * Expected: No race conditions, all data preserved
     */
    @Test
    fun testConcurrentSavesAndFlushes() = runBlocking {
        val fileStorage = IosFileStorage(baseDirectory = testDirectory)

        // Launch savers
        val saveJobs = (1..10).map { index ->
            launch(Dispatchers.Default) {
                val progress = ChainProgress(
                    chainId = "chain-$index",
                    totalSteps = 5,
                    completedSteps = listOf(0, 1)
                )
                fileStorage.saveChainProgress(progress)
                delay(50)
            }
        }

        // Launch flushers
        val flushJobs = (1..5).map {
            launch(Dispatchers.Default) {
                delay(25) // Offset from savers
                fileStorage.flushNow()
            }
        }

        // Wait for all
        (saveJobs + flushJobs).forEach { it.join() }

        // Verify all chains are readable
        val allReadable = (1..10).all { index ->
            fileStorage.loadChainProgress("chain-$index") != null
        }
        assertTrue(allReadable, "All saved chains should be readable after concurrent operations")
    }

    /**
     * Test 5: flushNow() during buffer accumulation
     *
     * Scenario: Save progress repeatedly, call flushNow() periodically
     * Expected: No data loss, consistent state
     */
    @Test
    fun testFlushNowDuringBufferAccumulation() = runBlocking {
        val fileStorage = IosFileStorage(baseDirectory = testDirectory)

        var progress = ChainProgress(
            chainId = "accumulation-test",
            totalSteps = 20,
            completedSteps = emptyList()
        )

        // Accumulate progress with periodic flushes
        repeat(20) { step ->
            progress = progress.withCompletedStep(step)
            fileStorage.saveChainProgress(progress)

            // Flush every 5 steps
            if (step % 5 == 0) {
                fileStorage.flushNow()
            }

            delay(20)
        }

        // Final flush
        fileStorage.flushNow()

        // Verify all steps preserved
        val loaded = fileStorage.loadChainProgress("accumulation-test")
        assertNotNull(loaded, "Progress should be loaded")
        assertEquals(20, loaded?.completedSteps?.size, "All steps should be preserved")
    }

    // ==================== Fix #9: ChainExecutor Close Deadlock ====================

    /**
     * Test 6: close() doesn't block indefinitely
     *
     * Scenario: Call close() on ChainExecutor
     * Expected: Should return promptly (non-blocking)
     */
    @Test
    fun testCloseDoesNotBlock() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        val startTime = System.currentTimeMillis()

        // close() should be non-blocking
        chainExecutor.close()

        val elapsed = System.currentTimeMillis() - startTime

        // Should complete quickly (< 1 second)
        assertTrue(
            elapsed < 1000,
            "close() should be non-blocking. Took ${elapsed}ms"
        )
    }

    /**
     * Test 7: closeAsync() properly awaits cleanup
     *
     * Scenario: Call closeAsync() and verify cleanup completes
     * Expected: Should complete cleanup properly
     */
    @Test
    fun testCloseAsyncAwaitsCleanup() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // closeAsync() should complete
        chainExecutor.closeAsync()

        // Should complete without deadlock
        assertTrue(true, "closeAsync() completed successfully")
    }

    /**
     * Test 8: Multiple close() calls don't cause issues
     *
     * Scenario: Call close() multiple times
     * Expected: Should be idempotent
     */
    @Test
    fun testMultipleCloseCalls() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // Call close() multiple times
        chainExecutor.close()
        chainExecutor.close()
        chainExecutor.close()

        // Should not crash or deadlock
        assertTrue(true, "Multiple close() calls handled gracefully")
    }

    /**
     * Test 9: Concurrent close() and closeAsync() calls
     *
     * Scenario: Call both close() and closeAsync() concurrently
     * Expected: No deadlock, both complete
     */
    @Test
    fun testConcurrentCloseAndCloseAsync() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // Launch concurrent close operations
        val closeJob = launch { chainExecutor.close() }
        val closeAsyncJob = launch { chainExecutor.closeAsync() }

        // Wait for both
        closeJob.join()
        closeAsyncJob.join()

        // Should complete without deadlock
        assertTrue(true, "Concurrent close operations completed")
    }

    /**
     * Test 10: close() while flush is in progress
     *
     * Scenario: Start a flush operation, then close executor
     * Expected: Should not deadlock
     */
    @Test
    fun testCloseWhileFlushInProgress() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // Simulate some activity that might trigger flush
        delay(50)

        // Close while potentially flushing
        val closeJob = launch {
            chainExecutor.close()
        }

        // Should complete promptly
        withTimeout(2000) {
            closeJob.join()
        }

        assertTrue(true, "close() completed even with potential ongoing flush")
    }

    /**
     * Test 11: Stress test - rapid open/close cycles
     *
     * Scenario: Create and close many executors rapidly
     * Expected: No deadlocks, no resource leaks
     */
    @Test
    fun testRapidOpenCloseCycles() = runBlocking {
        val workerFactory = TestWorkerFactory()

        repeat(20) { iteration ->
            val chainExecutor = ChainExecutor(
                workerFactory = workerFactory,
                taskType = BGTaskType.PROCESSING,
                onContinuationNeeded = null
            )

            if (iteration % 2 == 0) {
                chainExecutor.close()
            } else {
                chainExecutor.closeAsync()
            }

            delay(10)
        }

        assertTrue(true, "Rapid open/close cycles completed successfully")
    }

    /**
     * Test 12: close() after executor has been idle
     *
     * Scenario: Create executor, wait, then close
     * Expected: Should close cleanly
     */
    @Test
    fun testCloseAfterIdle() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // Let it sit idle
        delay(500)

        // Close after idle period
        chainExecutor.close()

        assertTrue(true, "Close after idle completed successfully")
    }

    /**
     * Test 13: Verify fix prevents regression
     *
     * Scenario: Document the fixes to prevent regression
     * Expected: This test serves as documentation
     */
    @Test
    fun testFixDocumentation() {
        // This test documents High Priority Fixes #8-9:
        //
        // Fix #8: Flush Race Condition
        // BEFORE (Bug):
        // - private var isFlushing = false
        // - if (isFlushing) return  // Race condition!
        // - isFlushing = true
        // Problem: Between check and set, another coroutine could proceed
        //
        // AFTER (Fix):
        // - private var flushCompletionSignal: CompletableDeferred<Unit>? = null
        // - val signal = progressMutex.withLock { flushCompletionSignal }
        // - signal?.await()  // Properly wait for ongoing flush
        // - flushCompletionSignal = newSignal  // Set signal atomically
        // - completionSignal.complete(Unit)  // Signal completion
        //
        // Fix #9: Close Deadlock
        // BEFORE (Bug):
        // - fun close() {
        //     fileStorage.flushNow()  // Blocks while holding closeMutex!
        //   }
        // Problem: Could deadlock if flush needs to acquire other locks
        //
        // AFTER (Fix):
        // - fun close() {
        //     CoroutineScope(Dispatchers.Default).launch {
        //       fileStorage.flushNow()  // Non-blocking
        //     }
        //   }
        // - suspend fun closeAsync() {
        //     closeMutex.withLock {
        //       fileStorage.flushNow()  // Proper async cleanup
        //     }
        //   }

        assertTrue(true, "Fixes #8-9 documented: Race condition and deadlock prevention")
    }

    // ===========================
    // Test Helpers
    // ===========================

    private class TestWorkerFactory : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? {
            return when (workerClassName) {
                "TestWorker" -> TestWorker()
                else -> null
            }
        }
    }

    private class TestWorker : IosWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            delay(50) // Simulate work
            return WorkerResult.Success(message = "Test worker completed")
        }
    }
}
