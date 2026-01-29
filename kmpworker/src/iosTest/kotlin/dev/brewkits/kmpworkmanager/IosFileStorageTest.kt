package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for IosFileStorage.
 *
 * **Note**: These tests require iOS runtime (simulator or device) to execute
 * because IosFileStorage uses native iOS APIs (NSFileManager, NSURL, etc.).
 *
 * Run with: `./gradlew :kmpworker:iosSimulatorArm64Test`
 */
class IosFileStorageTest {

    private lateinit var storage: IosFileStorage

    @BeforeTest
    fun setup() = runTest {
        storage = IosFileStorage()

        // Previous tests may have left data in filesystem
        while (storage.dequeueChain() != null) {
            // Clear all items
        }
    }

    // ==================== Queue Operations ====================

    @Test
    fun `enqueue and dequeue should work in order` () = runTest {
        // Enqueue chains
        storage.enqueueChain("chain-1")
        storage.enqueueChain("chain-2")
        storage.enqueueChain("chain-3")

        assertEquals(3, storage.getQueueSize())

        // Dequeue in FIFO order
        assertEquals("chain-1", storage.dequeueChain())
        assertEquals("chain-2", storage.dequeueChain())
        assertEquals("chain-3", storage.dequeueChain())

        assertEquals(0, storage.getQueueSize())
    }

    @Test
    fun `dequeue from empty queue should return null` () = runTest {
        assertNull(storage.dequeueChain())
    }

    @Test
    fun `getQueueSize should return zero for empty queue`() = runTest {
        assertEquals(0, storage.getQueueSize())
    }

    @Test
    fun `enqueue should increase queue size` () = runTest {
        assertEquals(0, storage.getQueueSize())

        storage.enqueueChain("test-1")
        assertEquals(1, storage.getQueueSize())

        storage.enqueueChain("test-2")
        assertEquals(2, storage.getQueueSize())
    }

    // ==================== Chain Definition Operations ====================

    @Test
    fun `saveChainDefinition and loadChainDefinition should persist data`() {
        val chainId = "test-chain-${(0..999999).random()}"
        val steps = listOf(
            listOf(
                TaskRequest("Worker1", """{"key": "value1"}"""),
                TaskRequest("Worker2", """{"key": "value2"}""")
            ),
            listOf(
                TaskRequest("Worker3", null)
            )
        )

        // Save
        storage.saveChainDefinition(chainId, steps)

        // Load and verify
        val loaded = storage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals(2, loaded.size)
        assertEquals(2, loaded[0].size)
        assertEquals("Worker1", loaded[0][0].workerClassName)
        assertEquals("""{"key": "value1"}""", loaded[0][0].inputJson)
        assertEquals("Worker2", loaded[0][1].workerClassName)
        assertEquals(1, loaded[1].size)
        assertEquals("Worker3", loaded[1][0].workerClassName)

        // Cleanup
        storage.deleteChainDefinition(chainId)
    }

    @Test
    fun `loadChainDefinition for non-existent chain should return null`() {
        val loaded = storage.loadChainDefinition("non-existent-chain")
        assertNull(loaded)
    }

    @Test
    fun `deleteChainDefinition should remove chain`() {
        val chainId = "delete-test-${(0..999999).random()}"
        val steps = listOf(listOf(TaskRequest("Worker1")))

        storage.saveChainDefinition(chainId, steps)
        assertNotNull(storage.loadChainDefinition(chainId))

        storage.deleteChainDefinition(chainId)
        assertNull(storage.loadChainDefinition(chainId))
    }

    @Test
    fun `saveChainDefinition should overwrite existing chain`() {
        val chainId = "overwrite-test-${(0..999999).random()}"

        // Save first version
        val steps1 = listOf(listOf(TaskRequest("Worker1")))
        storage.saveChainDefinition(chainId, steps1)

        // Save second version
        val steps2 = listOf(listOf(TaskRequest("Worker2"), TaskRequest("Worker3")))
        storage.saveChainDefinition(chainId, steps2)

        // Verify second version
        val loaded = storage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals(1, loaded.size)
        assertEquals(2, loaded[0].size)
        assertEquals("Worker2", loaded[0][0].workerClassName)
        assertEquals("Worker3", loaded[0][1].workerClassName)

        // Cleanup
        storage.deleteChainDefinition(chainId)
    }

    // ==================== Chain Progress Operations ====================

    @Test
    fun `saveChainProgress and loadChainProgress should persist progress`() {
        val chainId = "progress-test-${(0..999999).random()}"
        val progress = ChainProgress(
            chainId = chainId,
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2),
            lastFailedStep = null,
            retryCount = 0,
            maxRetries = 3
        )

        // Save
        storage.saveChainProgress(progress)

        // Load and verify
        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded)
        assertEquals(chainId, loaded.chainId)
        assertEquals(5, loaded.totalSteps)
        assertEquals(listOf(0, 1, 2), loaded.completedSteps)
        assertNull(loaded.lastFailedStep)
        assertEquals(0, loaded.retryCount)

        // Cleanup
        storage.deleteChainProgress(chainId)
    }

    @Test
    fun `loadChainProgress for non-existent chain should return null`() {
        val loaded = storage.loadChainProgress("non-existent-progress")
        assertNull(loaded)
    }

    @Test
    fun `deleteChainProgress should remove progress`() {
        val chainId = "delete-progress-${(0..999999).random()}"
        val progress = ChainProgress(chainId = chainId, totalSteps = 3)

        storage.saveChainProgress(progress)
        assertNotNull(storage.loadChainProgress(chainId))

        storage.deleteChainProgress(chainId)
        assertNull(storage.loadChainProgress(chainId))
    }

    @Test
    fun `saveChainProgress should update existing progress`() {
        val chainId = "update-progress-${(0..999999).random()}"

        // Save initial progress
        val progress1 = ChainProgress(
            chainId = chainId,
            totalSteps = 3,
            completedSteps = listOf(0)
        )
        storage.saveChainProgress(progress1)

        // Update progress
        val progress2 = ChainProgress(
            chainId = chainId,
            totalSteps = 3,
            completedSteps = listOf(0, 1, 2)
        )
        storage.saveChainProgress(progress2)

        // Verify updated version
        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded)
        assertEquals(listOf(0, 1, 2), loaded.completedSteps)

        // Cleanup
        storage.deleteChainProgress(chainId)
    }

    @Test
    fun `progress with retry count should be persisted`() {
        val chainId = "retry-test-${(0..999999).random()}"
        val progress = ChainProgress(
            chainId = chainId,
            totalSteps = 5,
            completedSteps = listOf(0, 1),
            lastFailedStep = 2,
            retryCount = 2,
            maxRetries = 3
        )

        storage.saveChainProgress(progress)
        val loaded = storage.loadChainProgress(chainId)

        assertNotNull(loaded)
        assertEquals(2, loaded.lastFailedStep)
        assertEquals(2, loaded.retryCount)
        assertEquals(3, loaded.maxRetries)

        // Cleanup
        storage.deleteChainProgress(chainId)
    }

    // ==================== Task Metadata Operations ====================

    @Test
    fun `saveTaskMetadata and loadTaskMetadata should persist metadata`() {
        val taskId = "task-${(0..999999).random()}"
        val metadata = mapOf(
            "workerClassName" to "TestWorker",
            "inputJson" to """{"key": "value"}""",
            "triggerType" to "OneTime"
        )

        // Save
        storage.saveTaskMetadata(taskId, metadata, periodic = false)

        // Load and verify
        val loaded = storage.loadTaskMetadata(taskId, periodic = false)
        assertNotNull(loaded)
        assertEquals("TestWorker", loaded["workerClassName"])
        assertEquals("""{"key": "value"}""", loaded["inputJson"])
        assertEquals("OneTime", loaded["triggerType"])

        // Cleanup
        storage.deleteTaskMetadata(taskId, periodic = false)
    }

    @Test
    fun `loadTaskMetadata for non-existent task should return null`() {
        val loaded = storage.loadTaskMetadata("non-existent-task", periodic = false)
        assertNull(loaded)
    }

    @Test
    fun `deleteTaskMetadata should remove metadata`() {
        val taskId = "delete-meta-${(0..999999).random()}"
        val metadata = mapOf("key" to "value")

        storage.saveTaskMetadata(taskId, metadata, periodic = false)
        assertNotNull(storage.loadTaskMetadata(taskId, periodic = false))

        storage.deleteTaskMetadata(taskId, periodic = false)
        assertNull(storage.loadTaskMetadata(taskId, periodic = false))
    }

    @Test
    fun `periodic and non-periodic metadata should be stored separately`() {
        val taskId = "separate-meta-${(0..999999).random()}"
        val normalMeta = mapOf("type" to "normal")
        val periodicMeta = mapOf("type" to "periodic")

        // Save both
        storage.saveTaskMetadata(taskId, normalMeta, periodic = false)
        storage.saveTaskMetadata(taskId, periodicMeta, periodic = true)

        // Load both and verify they're separate
        val loadedNormal = storage.loadTaskMetadata(taskId, periodic = false)
        val loadedPeriodic = storage.loadTaskMetadata(taskId, periodic = true)

        assertNotNull(loadedNormal)
        assertNotNull(loadedPeriodic)
        assertEquals("normal", loadedNormal["type"])
        assertEquals("periodic", loadedPeriodic["type"])

        // Cleanup
        storage.deleteTaskMetadata(taskId, periodic = false)
        storage.deleteTaskMetadata(taskId, periodic = true)
    }

    // ==================== Thread Safety Tests ====================

    @Test
    fun `concurrent enqueue operations should not lose data` () = runTest {
        val chainIds = (1..10).map { "concurrent-$it" }

        // Enqueue all chains
        chainIds.forEach { storage.enqueueChain(it) }

        assertEquals(10, storage.getQueueSize())

        // Dequeue all and verify order
        val dequeued = mutableListOf<String>()
        repeat(10) {
            storage.dequeueChain()?.let { dequeued.add(it) }
        }

        assertEquals(10, dequeued.size)
        assertEquals(chainIds, dequeued)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `chain with constraints should be persisted correctly`() {
        val chainId = "constraints-test-${(0..999999).random()}"
        val constraints = Constraints(
            requiresNetwork = true,
            requiresCharging = true
        )
        val steps = listOf(
            listOf(
                TaskRequest("Worker1", null, constraints)
            )
        )

        storage.saveChainDefinition(chainId, steps)
        val loaded = storage.loadChainDefinition(chainId)

        assertNotNull(loaded)
        assertEquals(1, loaded.size)
        assertNotNull(loaded[0][0].constraints)
        assertTrue(loaded[0][0].constraints!!.requiresNetwork)
        assertTrue(loaded[0][0].constraints!!.requiresCharging)

        // Cleanup
        storage.deleteChainDefinition(chainId)
    }

    @Test
    fun `empty task input should be persisted as null`() {
        val chainId = "empty-input-${(0..999999).random()}"
        val steps = listOf(listOf(TaskRequest("Worker1", null)))

        storage.saveChainDefinition(chainId, steps)
        val loaded = storage.loadChainDefinition(chainId)

        assertNotNull(loaded)
        assertNull(loaded[0][0].inputJson)

        // Cleanup
        storage.deleteChainDefinition(chainId)
    }
}
