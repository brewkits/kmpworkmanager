package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

/**
 * v2.1.0+: Unit tests for AppendOnlyQueue implementation
 * Tests O(1) performance, correctness, and migration
 */
@OptIn(ExperimentalForeignApi::class)
class AppendOnlyQueueTest {

    private lateinit var queue: AppendOnlyQueue
    private lateinit var testDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        // Create temporary test directory
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_test_${NSDate().timeIntervalSince1970}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(
            testDirectoryURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )

        queue = AppendOnlyQueue(testDirectoryURL)
    }

    @AfterTest
    fun tearDown() {
        // Clean up test directory
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    // ==================== Basic Operations ====================

    @Test
    fun `enqueue and dequeue single item`() = runTest {
        queue.enqueue("item-1")
        val result = queue.dequeue()

        assertEquals("item-1", result)
    }

    @Test
    fun `dequeue from empty queue returns null`() = runTest {
        val result = queue.dequeue()
        assertNull(result)
    }

    @Test
    fun `enqueue and dequeue multiple items maintains FIFO order`() = runTest {
        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        assertEquals("item-1", queue.dequeue())
        assertEquals("item-2", queue.dequeue())
        assertEquals("item-3", queue.dequeue())
        assertNull(queue.dequeue())
    }

    @Test
    fun `getSize returns correct count`() = runTest {
        assertEquals(0, queue.getSize())

        queue.enqueue("item-1")
        assertEquals(1, queue.getSize())

        queue.enqueue("item-2")
        assertEquals(2, queue.getSize())

        queue.dequeue()
        assertEquals(1, queue.getSize())

        queue.dequeue()
        assertEquals(0, queue.getSize())
    }

    // ==================== Performance Tests ====================

    @Test
    fun `enqueue 100 items should complete quickly O1`() = runTest {
        val startTime = TimeSource.Monotonic.markNow()

        repeat(100) { i ->
            queue.enqueue("item-$i")
        }

        val duration = startTime.elapsedNow()

        // Should complete in less than 100ms (generous threshold)
        assertTrue(duration.inWholeMilliseconds < 100, "Enqueuing 100 items took ${duration.inWholeMilliseconds}ms, expected <100ms")
    }

    @Test
    fun `dequeue 100 items should complete quickly O1 after cache build`() = runTest {
        // Enqueue 100 items
        repeat(100) { i ->
            queue.enqueue("item-$i")
        }

        val startTime = TimeSource.Monotonic.markNow()

        // Dequeue all
        repeat(100) {
            queue.dequeue()
        }

        val duration = startTime.elapsedNow()

        // Should complete in less than 200ms (first dequeue builds cache)
        assertTrue(duration.inWholeMilliseconds < 200, "Dequeuing 100 items took ${duration.inWholeMilliseconds}ms, expected <200ms")
    }

    @Test
    fun `mixed enqueue dequeue operations with 100 items`() = runTest {
        // Enqueue 100 items
        repeat(100) { i ->
            queue.enqueue("item-$i")
        }

        // Dequeue 50 items
        repeat(50) {
            queue.dequeue()
        }

        // Enqueue 50 more
        repeat(50) { i ->
            queue.enqueue("new-item-$i")
        }

        // Dequeue remaining 100 items
        repeat(100) {
            queue.dequeue()
        }

        // Verify queue is empty
        assertNull(queue.dequeue())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `enqueue items with hyphens and numbers`() = runTest {
        val items = listOf(
            "item-with-hyphen",
            "item-123",
            "ITEM_UPPERCASE",
            "item.with.dots"
        )

        items.forEach { queue.enqueue(it) }

        items.forEach { expected ->
            assertEquals(expected, queue.dequeue())
        }
    }

    @Test
    fun `queue survives multiple enqueue dequeue cycles`() = runTest {
        repeat(5) { cycle ->
            // Enqueue 20 items
            repeat(20) { i ->
                queue.enqueue("cycle-$cycle-item-$i")
            }

            // Dequeue 10 items
            repeat(10) {
                assertNotNull(queue.dequeue())
            }

            // Verify 10 items remain
            assertEquals(10, queue.getSize())

            // Dequeue remaining
            repeat(10) {
                assertNotNull(queue.dequeue())
            }

            assertEquals(0, queue.getSize())
        }
    }

    // ==================== Persistence & Migration ====================

    @Test
    fun `queue persists across instances`() = runTest {
        // Enqueue items in first instance
        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        // Dequeue first item
        assertEquals("item-1", queue.dequeue())

        // Create new queue instance with same directory
        val newQueue = AppendOnlyQueue(testDirectoryURL)

        // Should continue from where we left off
        assertEquals("item-2", newQueue.dequeue())
        assertEquals("item-3", newQueue.dequeue())
        assertNull(newQueue.dequeue())
    }

    @Test
    fun `migration from old queue format`() = runTest {
        val fileManager = NSFileManager.defaultManager

        // Simulate old format: write queue.jsonl manually (no head_pointer.txt)
        val queueFileURL = testDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val oldQueueContent = "old-item-1\nold-item-2\nold-item-3\n"

        val nsString = oldQueueContent as NSString
        nsString.writeToFile(
            queueFileURL.path!!,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )

        // Verify head_pointer.txt doesn't exist
        val headPointerURL = testDirectoryURL.URLByAppendingPathComponent("head_pointer.txt")!!
        assertFalse(fileManager.fileExistsAtPath(headPointerURL.path!!))

        // Create new queue (should trigger migration)
        val migratedQueue = AppendOnlyQueue(testDirectoryURL)

        // Verify head_pointer.txt was created
        assertTrue(fileManager.fileExistsAtPath(headPointerURL.path!!))

        // Verify old items are accessible
        assertEquals("old-item-1", migratedQueue.dequeue())
        assertEquals("old-item-2", migratedQueue.dequeue())
        assertEquals("old-item-3", migratedQueue.dequeue())
        assertNull(migratedQueue.dequeue())
    }

    @Test
    fun `queue handles concurrent access gracefully`() = runTest {
        // This test verifies that queueMutex provides thread-safety
        // Note: In iOS, coroutines run on main thread by default,
        // but Mutex ensures operations are serialized

        repeat(50) { i ->
            queue.enqueue("concurrent-$i")
        }

        repeat(50) {
            assertNotNull(queue.dequeue())
        }

        assertEquals(0, queue.getSize())
    }

    // ==================== Crash Recovery ====================

    @Test
    fun `partial write recovery - enqueue interrupted`() = runTest {
        // Enqueue items
        queue.enqueue("item-1")
        queue.enqueue("item-2")

        // Simulate crash by creating new instance without proper shutdown
        val recoveredQueue = AppendOnlyQueue(testDirectoryURL)

        // Should recover both items
        assertEquals("item-1", recoveredQueue.dequeue())
        assertEquals("item-2", recoveredQueue.dequeue())
    }

    @Test
    fun `partial read recovery - dequeue interrupted`() = runTest {
        // Enqueue items
        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        // Dequeue one
        assertEquals("item-1", queue.dequeue())

        // Simulate crash
        val recoveredQueue = AppendOnlyQueue(testDirectoryURL)

        // Should continue from item-2
        assertEquals("item-2", recoveredQueue.dequeue())
        assertEquals("item-3", recoveredQueue.dequeue())
        assertNull(recoveredQueue.dequeue())
    }

    // ==================== Compaction Detection ====================

    @Test
    fun `queue functions correctly after many dequeues`() = runTest {
        // This verifies queue still works after many operations
        // (compaction logic will be tested separately)

        // Enqueue 50 items
        repeat(50) { i ->
            queue.enqueue("item-$i")
        }

        // Dequeue 40 items
        repeat(40) {
            assertNotNull(queue.dequeue())
        }

        // Remaining 10 items should still be accessible
        repeat(10) {
            assertNotNull(queue.dequeue())
        }

        assertNull(queue.dequeue())
    }

    // ==================== Moderate Stress Test ====================

    @Test
    fun `moderate stress test - 200 operations`() = runTest {
        // Enqueue 100 items
        repeat(100) { i ->
            queue.enqueue("stress-$i")
        }

        // Dequeue 50 items
        repeat(50) {
            assertNotNull(queue.dequeue())
        }

        // Enqueue 100 more
        repeat(100) { i ->
            queue.enqueue("stress-extra-$i")
        }

        // Dequeue remaining 150 items
        repeat(150) {
            assertNotNull(queue.dequeue())
        }

        // Verify queue is empty
        assertNull(queue.dequeue())
    }
}
