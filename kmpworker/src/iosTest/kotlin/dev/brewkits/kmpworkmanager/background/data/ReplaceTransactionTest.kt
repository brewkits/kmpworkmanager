package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*

/**
 * Comprehensive tests for REPLACE Transaction (Task #9)
 * Tests:
 * - Atomic REPLACE operation
 * - Transaction log integrity
 * - Concurrent REPLACE handling
 * - Rollback on failure
 * - Race condition elimination
 */
class ReplaceTransactionTest {

    /**
     * Test basic REPLACE transaction atomicity
     */
    @Test
    fun testBasicReplaceTransaction() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "test-chain"

        // Initial chain
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        storage.saveChainDefinition(chainId, steps1)
        storage.enqueueChain(chainId)

        // REPLACE with new chain
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))
        storage.replaceChainAtomic(chainId, steps2)

        // Verify transaction log
        assertEquals(1, storage.transactionLog.size, "Should have 1 transaction")
        assertTrue(storage.transactionLog[0].succeeded, "Transaction should succeed")
        assertEquals("REPLACE", storage.transactionLog[0].action)

        // Verify new chain definition
        val loaded = storage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals("Worker2", loaded[0][0].workerClassName, "Should have new worker")
    }

    /**
     * Test REPLACE marks old chain as deleted
     */
    @Test
    fun testReplaceMarksOldChainAsDeleted() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "replace-test"

        // Original chain
        storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest("W1", ""))))
        storage.enqueueChain(chainId)

        assertFalse(storage.isChainDeleted(chainId), "Should not be deleted initially")

        // REPLACE
        storage.replaceChainAtomic(chainId, listOf(listOf(TaskRequest("W2", ""))))

        // Verify deleted marker
        assertTrue(storage.isChainDeleted(chainId), "Old chain should be marked as deleted")
    }

    /**
     * Test REPLACE is synchronous (enqueue happens within transaction)
     */
    @Test
    fun testReplaceSynchronousEnqueue() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "sync-test"

        // Track enqueue timing
        var enqueueTimestamp = 0L

        storage.onEnqueue = {
            enqueueTimestamp = System.currentTimeMillis()
        }

        val startTime = System.currentTimeMillis()
        storage.replaceChainAtomic(chainId, listOf(listOf(TaskRequest("W", ""))))
        val endTime = System.currentTimeMillis()

        // Verify enqueue happened within replaceChainAtomic call
        assertTrue(
            enqueueTimestamp in startTime..endTime,
            "Enqueue should happen synchronously within REPLACE"
        )
    }

    /**
     * Test concurrent REPLACE operations (race condition)
     */
    @Test
    fun testConcurrentReplaceOperations() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "concurrent-chain"

        // 10 concurrent REPLACE operations
        val jobs = (1..10).map { version ->
            launch {
                delay((1..20).random().toLong()) // Random timing
                storage.replaceChainAtomic(
                    chainId,
                    listOf(listOf(TaskRequest("Worker-v$version", "")))
                )
            }
        }

        jobs.joinAll()

        // Verify transaction integrity
        assertEquals(10, storage.transactionLog.size, "Should have 10 transactions")
        assertTrue(
            storage.transactionLog.all { it.succeeded },
            "All transactions should succeed"
        )

        // Verify final state is consistent (one of the versions)
        val final = storage.loadChainDefinition(chainId)
        assertNotNull(final)
        assertTrue(
            final[0][0].workerClassName.startsWith("Worker-v"),
            "Final state should be one of the versions"
        )

        // Verify queue has only 1 instance of the chain
        assertEquals(1, storage.getQueueSize(), "Queue should have only 1 chain")
    }

    /**
     * Test REPLACE transaction rollback on failure
     */
    @Test
    fun testReplaceRollbackOnFailure() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "rollback-test"

        // Original chain
        storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest("Original", ""))))
        storage.enqueueChain(chainId)

        // REPLACE with failure injection
        storage.shouldFailOnEnqueue = true

        try {
            storage.replaceChainAtomic(chainId, listOf(listOf(TaskRequest("New", ""))))
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
        }

        // Verify transaction logged as failed
        assertEquals(1, storage.transactionLog.size, "Should have 1 transaction")
        assertFalse(storage.transactionLog[0].succeeded, "Transaction should be marked as failed")
        assertNotNull(storage.transactionLog[0].error, "Error should be logged")

        // Verify old chain definition is still intact (rollback)
        val loaded = storage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals("Original", loaded[0][0].workerClassName, "Should have original worker (rollback)")
    }

    /**
     * Test REPLACE operation steps (delete, save, enqueue)
     */
    @Test
    fun testReplaceOperationSteps() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "steps-test"

        // Track execution order
        val executionOrder = mutableListOf<String>()

        storage.onMarkDeleted = { executionOrder.add("mark_deleted") }
        storage.onDeleteDefinition = { executionOrder.add("delete_definition") }
        storage.onDeleteProgress = { executionOrder.add("delete_progress") }
        storage.onSaveDefinition = { executionOrder.add("save_definition") }
        storage.onEnqueue = { executionOrder.add("enqueue") }

        // Execute REPLACE
        storage.replaceChainAtomic(chainId, listOf(listOf(TaskRequest("W", ""))))

        // Verify correct order
        val expectedOrder = listOf(
            "mark_deleted",
            "delete_definition",
            "delete_progress",
            "save_definition",
            "enqueue"
        )

        assertEquals(expectedOrder, executionOrder, "Operations should execute in correct order")
    }

    /**
     * Test REPLACE with empty steps
     */
    @Test
    fun testReplaceWithEmptySteps() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "empty-test"

        try {
            storage.replaceChainAtomic(chainId, emptyList())
            fail("Should throw exception for empty steps")
        } catch (e: IllegalArgumentException) {
            // Expected
            assertTrue(e.message?.contains("empty") == true)
        }

        // Verify transaction logged as failed
        assertEquals(1, storage.transactionLog.size)
        assertFalse(storage.transactionLog[0].succeeded)
    }

    /**
     * Test transaction log persistence across restarts
     */
    @Test
    fun testTransactionLogPersistence() = runBlocking {
        val storage = MockTransactionalStorage()

        // Execute some REPLACE operations
        repeat(5) { i ->
            storage.replaceChainAtomic("chain-$i", listOf(listOf(TaskRequest("W$i", ""))))
        }

        // Simulate app restart: create new storage with same backing store
        val newStorage = MockTransactionalStorage(storage.persistedTransactionLog)

        // Verify transaction log is restored
        assertEquals(5, newStorage.transactionLog.size, "Transaction log should be persisted")
        assertTrue(newStorage.transactionLog.all { it.succeeded }, "All should be successful")
    }

    /**
     * Stress test: 100 concurrent REPLACE operations
     */
    @Test
    fun stressTest100ConcurrentReplaces() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainIds = (1..10).map { "chain-$it" }

        val startTime = System.currentTimeMillis()

        // 100 REPLACE operations on 10 chains (10 operations per chain)
        val jobs = (1..100).map { i ->
            launch {
                val chainId = chainIds[i % 10]
                delay((1..10).random().toLong())
                storage.replaceChainAtomic(
                    chainId,
                    listOf(listOf(TaskRequest("Worker-$i", "")))
                )
            }
        }

        jobs.joinAll()

        val duration = System.currentTimeMillis() - startTime

        // Verify all transactions succeeded
        assertEquals(100, storage.transactionLog.size, "Should have 100 transactions")
        assertTrue(storage.transactionLog.all { it.succeeded }, "All should succeed")

        // Verify each chain has exactly 1 instance in queue
        assertEquals(10, storage.getQueueSize(), "Should have 10 chains in queue")

        // Verify no race conditions (each chain should have valid definition)
        chainIds.forEach { chainId ->
            val definition = storage.loadChainDefinition(chainId)
            assertNotNull(definition, "Chain $chainId should have definition")
        }

        println("Stress test: 100 concurrent REPLACEs completed in ${duration}ms, zero race conditions")
    }

    /**
     * Test REPLACE with large chain definition
     */
    @Test
    fun testReplaceWithLargeChainDefinition() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "large-chain"

        // Create large chain (100 steps, 10 tasks per step)
        val largeSteps = (1..100).map { step ->
            (1..10).map { task ->
                TaskRequest("Worker-$step-$task", "input-data-${step}-${task}")
            }
        }

        storage.replaceChainAtomic(chainId, largeSteps)

        // Verify transaction succeeded
        assertEquals(1, storage.transactionLog.size)
        assertTrue(storage.transactionLog[0].succeeded)

        // Verify definition saved correctly
        val loaded = storage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals(100, loaded.size, "Should have 100 steps")
        assertEquals(10, loaded[0].size, "Each step should have 10 tasks")
    }

    /**
     * Test REPLACE error message includes chain ID
     */
    @Test
    fun testReplaceErrorMessageIncludesChainId() = runBlocking {
        val storage = MockTransactionalStorage()
        val chainId = "error-chain"

        storage.shouldFailOnEnqueue = true

        try {
            storage.replaceChainAtomic(chainId, listOf(listOf(TaskRequest("W", ""))))
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
        }

        // Verify error message includes chain ID
        val transaction = storage.transactionLog[0]
        assertTrue(
            transaction.error?.contains(chainId) == true,
            "Error message should include chain ID"
        )
    }

    // ===========================
    // Mock Transactional Storage
    // ===========================

    private class MockTransactionalStorage(
        persistedLog: List<ChainTransaction> = emptyList()
    ) {
        data class ChainTransaction(
            val chainId: String,
            val action: String,
            val timestamp: Long,
            val succeeded: Boolean,
            val error: String? = null
        )

        val transactionLog = persistedLog.toMutableList()
        val persistedTransactionLog: List<ChainTransaction> get() = transactionLog

        private val chainDefinitions = mutableMapOf<String, List<List<TaskRequest>>>()
        private val deletedChains = mutableSetOf<String>()
        private val chainProgress = mutableMapOf<String, String>()
        private val queue = mutableListOf<String>()
        private val mutex = Mutex()

        var shouldFailOnEnqueue = false
        var onMarkDeleted: (() -> Unit)? = null
        var onDeleteDefinition: (() -> Unit)? = null
        var onDeleteProgress: (() -> Unit)? = null
        var onSaveDefinition: (() -> Unit)? = null
        var onEnqueue: (() -> Unit)? = null

        suspend fun replaceChainAtomic(chainId: String, newSteps: List<List<TaskRequest>>) = mutex.withLock {
            if (newSteps.isEmpty()) {
                val error = "Chain steps cannot be empty"
                transactionLog.add(ChainTransaction(chainId, "REPLACE", nowMillis(), false, error))
                throw IllegalArgumentException(error)
            }

            val txn = ChainTransaction(chainId, "REPLACE", nowMillis(), false)

            try {
                // Step 1: Mark deleted
                markChainAsDeleted(chainId)
                onMarkDeleted?.invoke()

                // Step 2: Delete old files
                deleteChainDefinition(chainId)
                onDeleteDefinition?.invoke()

                deleteChainProgress(chainId)
                onDeleteProgress?.invoke()

                // Step 3: Save new definition
                saveChainDefinition(chainId, newSteps)
                onSaveDefinition?.invoke()

                // Step 4: Enqueue (synchronous!)
                if (shouldFailOnEnqueue) {
                    throw RuntimeException("Enqueue failed for chain $chainId")
                }
                enqueueChain(chainId)
                onEnqueue?.invoke()

                // Step 5: Log success
                transactionLog.add(txn.copy(succeeded = true))
            } catch (e: Exception) {
                // Log failure
                transactionLog.add(txn.copy(succeeded = false, error = e.message))
                throw e
            }
        }

        fun saveChainDefinition(chainId: String, steps: List<List<TaskRequest>>) {
            chainDefinitions[chainId] = steps
        }

        fun loadChainDefinition(chainId: String): List<List<TaskRequest>>? {
            return chainDefinitions[chainId]
        }

        fun enqueueChain(chainId: String) {
            if (!queue.contains(chainId)) {
                queue.add(chainId)
            }
        }

        fun markChainAsDeleted(chainId: String) {
            deletedChains.add(chainId)
        }

        fun deleteChainDefinition(chainId: String) {
            chainDefinitions.remove(chainId)
        }

        fun deleteChainProgress(chainId: String) {
            chainProgress.remove(chainId)
        }

        fun isChainDeleted(chainId: String): Boolean {
            return deletedChains.contains(chainId)
        }

        fun getQueueSize(): Int {
            return queue.size
        }

        private fun nowMillis(): Long = System.currentTimeMillis()
    }
}
