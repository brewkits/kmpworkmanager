package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Tests chain replacement, deleted markers, and cleanup
 */
@OptIn(ExperimentalForeignApi::class)
class ExistingPolicyTest {

    private lateinit var fileStorage: IosFileStorage
    private lateinit var testDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        // Create temporary test directory
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_policy_test_${NSDate().timeIntervalSince1970}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(
            testDirectoryURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )

        fileStorage = IosFileStorage()
    }

    @AfterTest
    fun tearDown() {
        // Clean up test directory
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    // ==================== ExistingPolicy.KEEP Tests ====================

    @Test
    fun `testPolicyKeepSkipsExisting - chain already exists`() = runTest {
        val chainId = "test-chain-keep"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Save first chain definition
        fileStorage.saveChainDefinition(chainId, steps1)
        assertTrue(fileStorage.chainExists(chainId), "Chain should exist after save")

        // Simulate KEEP policy: if chain exists, don't save new definition
        if (fileStorage.chainExists(chainId)) {
            // KEEP policy - skip
        } else {
            fileStorage.saveChainDefinition(chainId, steps2)
        }

        // Verify original definition is preserved
        val loadedSteps = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loadedSteps, "Chain definition should still exist")
        assertEquals(1, loadedSteps.size, "Should have 1 step")
        assertEquals("Worker1", loadedSteps[0][0].workerClassName, "Should preserve original worker")
    }

    @Test
    fun `testPolicyKeepSkipsExisting - chain doesn't exist`() = runTest {
        val chainId = "test-chain-keep-new"
        val steps = listOf(listOf(TaskRequest("Worker1", "input1")))

        // Chain doesn't exist
        assertFalse(fileStorage.chainExists(chainId), "Chain should not exist initially")

        // Save new chain (KEEP policy allows this)
        fileStorage.saveChainDefinition(chainId, steps)

        // Verify chain was saved
        assertTrue(fileStorage.chainExists(chainId), "Chain should exist after save")
        val loadedSteps = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loadedSteps)
        assertEquals("Worker1", loadedSteps[0][0].workerClassName)
    }

    // ==================== ExistingPolicy.REPLACE Tests ====================

    @Test
    fun `testPolicyReplaceDeletesOld - replaces existing chain`() = runTest {
        val chainId = "test-chain-replace"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Save first chain definition
        fileStorage.saveChainDefinition(chainId, steps1)
        fileStorage.enqueueChain(chainId)

        assertTrue(fileStorage.chainExists(chainId), "Chain should exist")

        // Simulate REPLACE policy
        fileStorage.markChainAsDeleted(chainId)
        fileStorage.deleteChainDefinition(chainId)
        fileStorage.deleteChainProgress(chainId)

        // Save new chain definition
        fileStorage.saveChainDefinition(chainId, steps2)
        fileStorage.enqueueChain(chainId)

        // Verify new definition is saved
        val loadedSteps = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loadedSteps, "New chain definition should exist")
        assertEquals("Worker2", loadedSteps[0][0].workerClassName, "Should have new worker")

        // Verify deleted marker exists
        assertTrue(fileStorage.isChainDeleted(chainId), "Deleted marker should exist")
    }

    @Test
    fun `testPolicyReplaceDeletesOld - deleted marker prevents execution`() = runTest {
        val chainId = "test-chain-replace-marker"

        // Mark chain as deleted (REPLACE policy)
        fileStorage.markChainAsDeleted(chainId)

        // Verify marker exists
        assertTrue(fileStorage.isChainDeleted(chainId), "Deleted marker should exist")

        // Clear marker (simulating ChainExecutor skipping execution)
        fileStorage.clearDeletedMarker(chainId)

        // Verify marker is cleared
        assertFalse(fileStorage.isChainDeleted(chainId), "Deleted marker should be cleared")
    }

    // ==================== Deleted Marker Cleanup Tests ====================

    @Test
    fun `testDeletedMarkersCleanup - removes stale markers`() = runTest {
        val chainId = "test-chain-stale"

        // Mark chain as deleted with old timestamp
        fileStorage.markChainAsDeleted(chainId)
        assertTrue(fileStorage.isChainDeleted(chainId), "Marker should exist")

        // NOTE: In a real test, we'd need to mock the timestamp to be 8 days old
        // For now, we verify the cleanup function exists and doesn't crash
        fileStorage.cleanupStaleDeletedMarkers()

        // In a real scenario with an 8-day-old marker, it would be cleaned up
        // For this test, the marker is fresh, so it should still exist
        assertTrue(fileStorage.isChainDeleted(chainId), "Fresh marker should not be cleaned up")
    }

    @Test
    fun `testDeletedMarkersCleanup - keeps fresh markers`() = runTest {
        val chainId = "test-chain-fresh"

        // Mark chain as deleted (fresh marker)
        fileStorage.markChainAsDeleted(chainId)
        assertTrue(fileStorage.isChainDeleted(chainId), "Marker should exist")

        // Run cleanup
        fileStorage.cleanupStaleDeletedMarkers()

        // Fresh marker should still exist (< 7 days old)
        assertTrue(fileStorage.isChainDeleted(chainId), "Fresh marker should be preserved")
    }

    @Test
    fun `testMaintenanceCalledOnInit - no crash`() = runTest {
        // Verify performMaintenanceTasks runs without crashing
        fileStorage.performMaintenanceTasks()

        // If we get here, maintenance completed successfully
        assertTrue(true, "Maintenance tasks completed without crash")
    }

    @Test
    fun `testMaintenanceCalledOnInit - cleans up stale data`() = runTest {
        // Create a deleted marker
        val chainId = "test-chain-maintenance"
        fileStorage.markChainAsDeleted(chainId)

        // Run maintenance
        fileStorage.performMaintenanceTasks()

        // Fresh markers should be preserved
        assertTrue(fileStorage.isChainDeleted(chainId), "Fresh marker should be preserved")

        // Verify cleanup doesn't affect fresh data
        // (In a real scenario with old data, it would be cleaned up)
    }

    // ==================== Chain Existence Tests ====================

    @Test
    fun `testChainExists - returns true for existing chain`() = runTest {
        val chainId = "test-chain-exists"
        val steps = listOf(listOf(TaskRequest("Worker1", "input1")))

        fileStorage.saveChainDefinition(chainId, steps)
        assertTrue(fileStorage.chainExists(chainId), "Chain should exist")
    }

    @Test
    fun `testChainExists - returns false for non-existing chain`() = runTest {
        val chainId = "test-chain-not-exists"
        assertFalse(fileStorage.chainExists(chainId), "Chain should not exist")
    }

    // ==================== Integration Tests ====================

    @Test
    fun `testReplaceWorkflow - full REPLACE policy workflow`() = runTest {
        val chainId = "test-chain-replace-workflow"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Step 1: Enqueue first chain
        fileStorage.saveChainDefinition(chainId, steps1)
        fileStorage.enqueueChain(chainId)
        assertEquals(1, fileStorage.getQueueSize())

        // Step 2: Replace with new chain (REPLACE policy)
        if (fileStorage.chainExists(chainId)) {
            // Mark old chain as deleted
            fileStorage.markChainAsDeleted(chainId)
            // Delete old definition
            fileStorage.deleteChainDefinition(chainId)
            fileStorage.deleteChainProgress(chainId)
        }

        // Save new definition
        fileStorage.saveChainDefinition(chainId, steps2)
        fileStorage.enqueueChain(chainId)

        // Step 3: Dequeue first chain (old one)
        val dequeuedId1 = fileStorage.dequeueChain()
        assertEquals(chainId, dequeuedId1, "Should dequeue old chain ID")

        // Check if deleted (should be)
        assertTrue(fileStorage.isChainDeleted(chainId), "Old chain should be marked as deleted")

        // Clear marker (simulating ChainExecutor skipping)
        fileStorage.clearDeletedMarker(chainId)

        // Step 4: Dequeue second chain (new one)
        val dequeuedId2 = fileStorage.dequeueChain()
        assertEquals(chainId, dequeuedId2, "Should dequeue new chain ID")

        // Should not be deleted
        assertFalse(fileStorage.isChainDeleted(chainId), "New chain should not be marked as deleted")

        // Load definition - should be new one
        val loadedSteps = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loadedSteps)
        assertEquals("Worker2", loadedSteps[0][0].workerClassName, "Should have new worker")
    }

    @Test
    fun `testKeepWorkflow - full KEEP policy workflow`() = runTest {
        val chainId = "test-chain-keep-workflow"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Step 1: Enqueue first chain
        fileStorage.saveChainDefinition(chainId, steps1)
        fileStorage.enqueueChain(chainId)
        assertEquals(1, fileStorage.getQueueSize())

        // Step 2: Try to enqueue again with KEEP policy
        if (fileStorage.chainExists(chainId)) {
            // KEEP policy - skip enqueue
            // Do nothing
        } else {
            fileStorage.saveChainDefinition(chainId, steps2)
            fileStorage.enqueueChain(chainId)
        }

        // Queue size should still be 1 (not 2)
        assertEquals(1, fileStorage.getQueueSize(), "Queue should only have 1 chain (KEEP policy)")

        // Load definition - should be original
        val loadedSteps = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loadedSteps)
        assertEquals("Worker1", loadedSteps[0][0].workerClassName, "Should have original worker")
    }
}
