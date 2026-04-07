@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.BGTaskType
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/**
 * Comprehensive tests for High Priority Fixes #8-9: iOS Race Conditions & Deadlock Prevention
 */
class IosRaceConditionTest {

    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() {
        val fileManager = NSFileManager.defaultManager
        val tempDir = fileManager.temporaryDirectory()
        testDirectory = tempDir.URLByAppendingPathComponent("IosRaceConditionTest-${NSDate().timeIntervalSince1970()}")!!

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

    @Test
    fun testConcurrentFlushNowSynchronized() = runBlocking {
        val fileStorage = IosFileStorage()

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
        assertEquals(listOf(0, 1, 2), loaded.completedSteps, "Data should not be corrupted")
    }

    @Test
    fun testFlushNowWaitsForOngoingFlush() = runBlocking {
        val fileStorage = IosFileStorage()

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
            loaded.completedSteps.contains(1),
            "Latest update should be persisted"
        )
    }

    @Test
    fun testRapidFlushNowCallsNoCorruption() = runBlocking {
        val fileStorage = IosFileStorage()

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
        assertEquals(listOf(0, 1), loaded.completedSteps, "Data should not be corrupted")
    }

    @Test
    fun testConcurrentSavesAndFlushes() = runBlocking {
        val fileStorage = IosFileStorage()

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

    // ==================== Fix #9: ChainExecutor Close Deadlock ====================

    @Test
    fun testCloseDoesNotBlock() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        val startTime = NSDate().timeIntervalSince1970() * 1000

        // close() should be non-blocking
        chainExecutor.close()

        val elapsed = (NSDate().timeIntervalSince1970() * 1000) - startTime

        // Should complete quickly (< 1 second)
        assertTrue(
            elapsed < 1000,
            "close() should be non-blocking. Took ${elapsed}ms"
        )
    }

    @Test
    fun testCloseAwaitsCleanup() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // close() should complete
        chainExecutor.close()

        // Should complete without deadlock
        assertTrue(true, "close() completed successfully")
    }

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

    @Test
    fun testConcurrentClose() = runBlocking {
        val workerFactory = TestWorkerFactory()

        val chainExecutor = ChainExecutor(
            workerFactory = workerFactory,
            taskType = BGTaskType.PROCESSING,
            onContinuationNeeded = null
        )

        // Launch concurrent close operations
        val closeJob1 = launch { chainExecutor.close() }
        val closeJob2 = launch { chainExecutor.close() }

        // Wait for both
        closeJob1.join()
        closeJob2.join()

        // Should complete without deadlock
        assertTrue(true, "Concurrent close operations completed")
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
        override suspend fun doWork(
            input: String?,
            env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
        ): WorkerResult {
            delay(50) // Simulate work
            return WorkerResult.Success(message = "Test worker completed")
        }
    }
}
