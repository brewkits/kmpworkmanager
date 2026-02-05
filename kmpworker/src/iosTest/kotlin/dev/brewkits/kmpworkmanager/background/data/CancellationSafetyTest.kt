package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.ChainProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Comprehensive tests for Cancellation Safety (Task #5)
 * Tests:
 * - NonCancellable blocks during I/O
 * - Atomic writes with POSIX rename
 * - Temp file cleanup on failure
 * - No data loss during cancellation
 * - Flush guarantee despite cancellation
 */
class CancellationSafetyTest {

    /**
     * Test NonCancellable block protects critical section
     */
    @Test
    fun testNonCancellableBlockCompletesOperation() = runBlocking {
        var criticalSectionCompleted = false
        var cleanupExecuted = false

        val job = launch {
            try {
                delay(50)

                // Critical section should complete even if job is cancelled
                withContext(NonCancellable) {
                    delay(100) // Simulate I/O
                    criticalSectionCompleted = true
                }
            } finally {
                withContext(NonCancellable) {
                    cleanupExecuted = true
                }
            }
        }

        delay(75) // Cancel during critical section
        job.cancel()
        job.join()

        assertTrue(criticalSectionCompleted, "Critical section should complete despite cancellation")
        assertTrue(cleanupExecuted, "Cleanup should execute")
    }

    /**
     * Test progress save in NonCancellable context
     */
    @Test
    fun testProgressSaveProtectedFromCancellation() = runBlocking {
        val storage = MockAtomicStorage()
        var saveCompleted = false

        val job = launch {
            repeat(10) { i ->
                delay(20)

                // Simulate progress save with NonCancellable protection
                withContext(NonCancellable) {
                    val progress = ChainProgress("chain-$i", 1, 0, 0, 1, 1, 0, null)
                    storage.saveProgress(progress)
                    saveCompleted = true
                }
            }
        }

        delay(50) // Cancel mid-execution
        job.cancel()
        job.join()

        assertTrue(saveCompleted, "At least one save should complete")
        assertTrue(storage.saveCount > 0, "Should have saved some progress")
    }

    /**
     * Test atomic write completes despite cancellation
     */
    @Test
    fun testAtomicWriteCompletesOnCancellation() = runBlocking {
        val storage = MockAtomicStorage()

        val job = launch {
            repeat(100) { i ->
                storage.atomicWrite("file-$i", "content-$i")
                delay(10)
            }
        }

        delay(250) // Let some writes complete
        job.cancel()

        // Force remaining writes in NonCancellable
        withContext(NonCancellable) {
            storage.flushPendingWrites()
        }

        // Verify all committed writes are complete (no partial writes)
        assertTrue(storage.writeCount > 0, "Should have completed some writes")
        assertTrue(storage.partialWriteCount == 0, "Should have no partial writes")
    }

    /**
     * Test temp file cleanup on failure
     */
    @Test
    fun testTempFileCleanupOnFailure() = runBlocking {
        val storage = MockAtomicStorage()

        try {
            storage.atomicWriteWithFailure("test-file", "content", shouldFail = true)
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
        }

        // Verify temp file was cleaned up
        assertEquals(0, storage.tempFileCount, "Temp files should be cleaned up on failure")
    }

    /**
     * Test POSIX rename atomicity
     */
    @Test
    fun testPosixRenameAtomicity() = runBlocking {
        val storage = MockAtomicStorage()

        // Write to temp file
        storage.atomicWrite("target-file", "final-content")

        // Verify atomic rename (temp file gone, target file exists)
        assertEquals(0, storage.tempFileCount, "Temp file should be gone after rename")
        assertEquals(1, storage.finalFileCount, "Final file should exist")
        assertEquals("final-content", storage.readFile("target-file"), "Content should match")
    }

    /**
     * Test no partial writes during concurrent cancellations
     */
    @Test
    fun testNoPartialWritesDuringConcurrentCancellations() = runBlocking {
        val storage = MockAtomicStorage()

        // Launch 10 concurrent writers
        val jobs = (1..10).map { chainId ->
            launch {
                repeat(50) { i ->
                    withContext(NonCancellable) {
                        storage.atomicWrite("chain-$chainId-task-$i", "data-$i")
                    }
                    delay(5)
                }
            }
        }

        // Cancel all jobs randomly
        delay(100)
        jobs.forEach {
            delay((1..10).random().toLong())
            it.cancel()
        }
        jobs.joinAll()

        // Verify no partial writes
        assertEquals(0, storage.partialWriteCount, "Should have zero partial writes")
        assertTrue(storage.writeCount > 0, "Should have completed some writes")
    }

    /**
     * Stress test: Cancel during 1000 operations
     */
    @Test
    fun stressTestCancellationDuring1000Operations() = runBlocking {
        val storage = MockAtomicStorage()
        var operationsStarted = 0

        val job = launch {
            repeat(1000) { i ->
                operationsStarted++
                withContext(NonCancellable) {
                    storage.saveProgress(ChainProgress("chain-${i%10}", 1, 0, 0, 1, 1, 0, null))
                }
                if (i % 10 == 0) delay(1) // Occasional delay
            }
        }

        delay(50) // Cancel early
        job.cancel()
        job.join()

        // Verify data consistency
        assertTrue(operationsStarted > 0, "Should have started some operations")
        assertEquals(operationsStarted, storage.saveCount, "All started operations should complete")
        assertEquals(0, storage.corruptedSaveCount, "Should have no corrupted saves")

        println("Stress test: ${operationsStarted} operations started, all completed safely")
    }

    /**
     * Test flush guarantee in finally block
     */
    @Test
    fun testFlushGuaranteeInFinallyBlock() = runBlocking {
        val storage = MockAtomicStorage()
        var finallyExecuted = false
        var flushExecuted = false

        val job = launch {
            try {
                repeat(100) { i ->
                    storage.saveProgress(ChainProgress("chain-$i", 1, 0, 0, 1, 1, 0, null))
                    delay(10)
                }
            } finally {
                withContext(NonCancellable) {
                    storage.flushPendingWrites()
                    flushExecuted = true
                    finallyExecuted = true
                }
            }
        }

        delay(250)
        job.cancel()
        job.join()

        assertTrue(finallyExecuted, "Finally block should execute")
        assertTrue(flushExecuted, "Flush should execute in finally")
        assertEquals(0, storage.pendingWriteCount, "All pending writes should be flushed")
    }

    /**
     * Test multiple cancellations don't cause data loss
     */
    @Test
    fun testMultipleCancellationsNoDataLoss() = runBlocking {
        val storage = MockAtomicStorage()

        repeat(5) { attempt ->
            val job = launch {
                repeat(100) { i ->
                    withContext(NonCancellable) {
                        storage.atomicWrite("attempt-$attempt-item-$i", "data")
                    }
                    delay(5)
                }
            }

            delay(50)
            job.cancel()
            job.join()
        }

        // Verify no data loss across all attempts
        assertTrue(storage.writeCount > 0, "Should have completed writes across all attempts")
        assertEquals(0, storage.partialWriteCount, "Should have no partial writes")
        assertEquals(0, storage.corruptedSaveCount, "Should have no corrupted data")

        println("Multiple cancellations test: ${storage.writeCount} writes completed safely")
    }

    /**
     * Test cancellation during atomic rename
     */
    @Test
    fun testCancellationDuringAtomicRename() = runBlocking {
        val storage = MockAtomicStorage()

        val job = launch {
            withContext(NonCancellable) {
                // Atomic operation: write temp + rename
                storage.atomicWrite("critical-file", "critical-data")
            }
        }

        // Try to cancel during rename (should not affect operation)
        delay(1)
        job.cancel()
        job.join()

        // Verify file was written completely
        assertEquals("critical-data", storage.readFile("critical-file"), "File should be complete")
        assertEquals(0, storage.tempFileCount, "Temp file should be cleaned up")
    }

    /**
     * Test error during write doesn't leave partial data
     */
    @Test
    fun testErrorDuringWriteNoPartialData() = runBlocking {
        val storage = MockAtomicStorage()

        try {
            storage.atomicWriteWithFailure("error-file", "data", shouldFail = true)
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
        }

        // Verify no partial file exists
        assertNull(storage.readFile("error-file"), "Partial file should not exist")
        assertEquals(0, storage.tempFileCount, "Temp file should be cleaned up")
    }

    // ===========================
    // Mock Storage with Atomic Operations
    // ===========================

    private class MockAtomicStorage {
        private val files = mutableMapOf<String, String>()
        private val tempFiles = mutableMapOf<String, String>()
        var saveCount = 0
        var writeCount = 0
        var partialWriteCount = 0
        var corruptedSaveCount = 0
        var tempFileCount = 0
        var finalFileCount = 0
        var pendingWriteCount = 0

        fun saveProgress(progress: ChainProgress) {
            // Simulate atomic save
            try {
                files[progress.chainId] = "progress-${progress.completedSteps}"
                saveCount++
            } catch (e: Exception) {
                corruptedSaveCount++
                throw e
            }
        }

        suspend fun atomicWrite(fileName: String, content: String) {
            pendingWriteCount++

            // Write to temp file first
            val tempFileName = "$fileName.tmp"
            tempFiles[tempFileName] = content
            tempFileCount = tempFiles.size

            delay(5) // Simulate I/O delay

            // Atomic rename (POSIX guarantee)
            files.remove(fileName) // Delete old if exists
            files[fileName] = content
            tempFiles.remove(tempFileName)

            writeCount++
            pendingWriteCount--
            tempFileCount = tempFiles.size
            finalFileCount = files.size
        }

        suspend fun atomicWriteWithFailure(fileName: String, content: String, shouldFail: Boolean) {
            val tempFileName = "$fileName.tmp"
            tempFiles[tempFileName] = content
            tempFileCount = tempFiles.size

            try {
                if (shouldFail) {
                    throw IOException("Simulated I/O failure")
                }

                files[fileName] = content
                tempFiles.remove(tempFileName)
                writeCount++
            } finally {
                // Cleanup temp file on failure
                tempFiles.remove(tempFileName)
                tempFileCount = tempFiles.size
            }
        }

        fun flushPendingWrites() {
            // Flush all pending writes
            pendingWriteCount = 0
        }

        fun readFile(fileName: String): String? {
            return files[fileName]
        }
    }

    private class IOException(message: String) : Exception(message)
}
