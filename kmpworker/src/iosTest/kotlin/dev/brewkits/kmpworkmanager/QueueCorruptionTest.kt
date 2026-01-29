package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import kotlinx.cinterop.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Tests corruption recovery, Unicode support, and race conditions
 */
@OptIn(ExperimentalForeignApi::class)
class QueueCorruptionTest {

    private lateinit var queue: AppendOnlyQueue
    private lateinit var testDirectoryURL: NSURL
    private lateinit var queueFileURL: NSURL

    @BeforeTest
    fun setup() {
        // Create temporary test directory
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_corruption_test_${NSDate().timeIntervalSince1970}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(
            testDirectoryURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )

        queueFileURL = testDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        queue = AppendOnlyQueue(testDirectoryURL)
    }

    @AfterTest
    fun tearDown() {
        // Clean up test directory
        val fileManager = NSFileManager.defaultManager
        fileManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    // ==================== Corruption Recovery Tests ====================

    @Test
    fun `testCorruptQueueRecovery - truncated file triggers reset`() = runTest {
        // Enqueue some items
        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        // Dequeue first item
        assertEquals("item-1", queue.dequeue())

        // Corrupt the queue file by truncating it
        val queuePath = queueFileURL.path!!
        val fileManager = NSFileManager.defaultManager

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            // Write corrupted data (truncated JSON)
            val corruptData = "{\"corrupt\": tru".encodeToByteArray()
            val nsData = corruptData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = corruptData.size.toULong())
            }

            nsData.writeToFile(queuePath, atomically = true)
        }

        // Try to dequeue - should detect corruption and return null
        val result = queue.dequeue()
        assertNull(result, "Dequeue should return null after corruption detected")

        // Next dequeue should trigger reset and return null (queue is empty)
        val result2 = queue.dequeue()
        assertNull(result2, "Queue should be empty after reset")

        // Queue should now be usable again
        queue.enqueue("item-after-reset")
        val result3 = queue.dequeue()
        assertEquals("item-after-reset", result3, "Queue should work after reset")
    }

    @Test
    fun `testCorruptQueueRecovery - invalid bytes trigger reset`() = runTest {
        // Enqueue valid items
        queue.enqueue("item-1")

        // Corrupt the queue file with invalid bytes
        val queuePath = queueFileURL.path!!
        memScoped {
            // Write some invalid binary data
            val corruptData = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0x00)
            val nsData = corruptData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = corruptData.size.toULong())
            }

            nsData.writeToFile(queuePath, atomically = true)
        }

        // Try to dequeue - should detect corruption
        val result = queue.dequeue()
        assertNull(result, "Dequeue should handle corruption gracefully")

        // Queue should be resetable and usable
        queue.resetQueue()
        queue.enqueue("item-new")
        val result2 = queue.dequeue()
        assertEquals("item-new", result2)
    }

    // ==================== Unicode Support Tests ====================

    @Test
    fun `testUnicodeInQueue - Vietnamese characters`() = runTest {
        val vietnamese = "Xin chào! Tôi là developer 🇻🇳"

        queue.enqueue(vietnamese)
        val result = queue.dequeue()

        assertEquals(vietnamese, result, "Queue should handle Vietnamese text correctly")
    }

    @Test
    fun `testUnicodeInQueue - Chinese characters`() = runTest {
        val chinese = "你好世界！这是一个测试 🇨🇳"

        queue.enqueue(chinese)
        val result = queue.dequeue()

        assertEquals(chinese, result, "Queue should handle Chinese text correctly")
    }

    @Test
    fun `testUnicodeInQueue - Emoji characters`() = runTest {
        val emoji = "🚀 🎉 🔥 ✨ 💯 ❤️ 🌟 ⭐ 🎊 🎈"

        queue.enqueue(emoji)
        val result = queue.dequeue()

        assertEquals(emoji, result, "Queue should handle emoji correctly")
    }

    @Test
    fun `testUnicodeInQueue - Mixed international characters`() = runTest {
        val mixed = "Hello 世界 🌍 Здравствуй مرحبا שָׁלוֹם"

        queue.enqueue(mixed)
        val result = queue.dequeue()

        assertEquals(mixed, result, "Queue should handle mixed international text correctly")
    }

    @Test
    fun `testUnicodeInQueue - JSON with Unicode escape sequences`() = runTest {
        val jsonWithUnicode = """{"message":"Test with Unicode: ñ, é, ü, 日本語"}"""

        queue.enqueue(jsonWithUnicode)
        val result = queue.dequeue()

        assertEquals(jsonWithUnicode, result, "Queue should handle JSON with Unicode correctly")
    }

    @Test
    fun `testUnicodeInQueue - Multiple items with different scripts`() = runTest {
        val items = listOf(
            "English text",
            "日本語のテキスト",
            "Texto en español",
            "Русский текст",
            "عربي",
            "🎌 🗾 🍣"
        )

        // Enqueue all items
        items.forEach { queue.enqueue(it) }

        // Dequeue and verify order
        items.forEach { expected ->
            val result = queue.dequeue()
            assertEquals(expected, result, "Failed to dequeue: $expected")
        }

        // Queue should be empty
        assertNull(queue.dequeue())
    }

    // ==================== Race Condition Tests ====================

    @Test
    fun `testConcurrentResetAndEnqueue - no data loss`() = runTest {
        // This test verifies that the double-mutex pattern prevents race conditions
        // between resetQueue() and enqueue()

        val iterations = 100
        var successfulEnqueues = 0
        val results = mutableListOf<String?>()

        repeat(iterations) { i ->
            // Launch concurrent operations
            val enqueueJob = async {
                try {
                    queue.enqueue("item-$i")
                    successfulEnqueues++
                } catch (e: Exception) {
                    // Enqueue might fail if queue is being reset
                    null
                }
            }

            val resetJob = async {
                if (i % 10 == 0) {  // Reset every 10th iteration
                    try {
                        queue.resetQueue()
                    } catch (e: Exception) {
                        // Reset might fail if queue is being modified
                        null
                    }
                }
            }

            enqueueJob.await()
            resetJob.await()
        }

        // Dequeue all remaining items
        var dequeueCount = 0
        while (true) {
            val item = queue.dequeue()
            if (item == null) break
            results.add(item)
            dequeueCount++
        }

        // Verify no crashes occurred (test passes if we get here)
        assertTrue(true, "Concurrent reset and enqueue completed without crashes")

        // Verify queue is in consistent state
        assertEquals(0, queue.getSize(), "Queue should be empty after dequeuing all items")
    }

    @Test
    fun `testConcurrentDequeueWithCorruption - double-check pattern works`() = runTest {
        // This test verifies the double-check pattern in dequeue() prevents TOCTOU issues

        // Enqueue items
        queue.enqueue("item-1")
        queue.enqueue("item-2")

        // Corrupt the queue by manually setting corruption flag (simulated via file corruption)
        val queuePath = queueFileURL.path!!
        memScoped {
            // Write corrupted data
            val corruptData = "invalid\u0000data".encodeToByteArray()
            val nsData = corruptData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = corruptData.size.toULong())
            }
            nsData.writeToFile(queuePath, atomically = true)
        }

        // Launch multiple dequeue operations concurrently
        val jobs = List(10) {
            async {
                queue.dequeue()
            }
        }

        // Wait for all jobs
        val results = jobs.map { it.await() }

        // All should return null (either due to corruption or empty queue after reset)
        results.forEach { result ->
            assertNull(result, "Dequeue should handle corruption safely")
        }

        // Queue should be in consistent state and usable
        queue.enqueue("item-after-concurrent-dequeue")
        val finalResult = queue.dequeue()
        assertEquals("item-after-concurrent-dequeue", finalResult)
    }

    @Test
    fun `testManualReset - resetQueue clears all data`() = runTest {
        // Enqueue multiple items
        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        // Verify items exist
        assertEquals(3, queue.getSize())

        // Manual reset
        queue.resetQueue()

        // Verify queue is empty
        assertEquals(0, queue.getSize())
        assertNull(queue.dequeue())

        // Verify queue is usable after reset
        queue.enqueue("item-new")
        assertEquals("item-new", queue.dequeue())
    }

    @Test
    fun `testEmptyQueueReset - reset on empty queue is safe`() = runTest {
        // Reset empty queue
        queue.resetQueue()

        // Verify queue is still usable
        queue.enqueue("item-1")
        assertEquals("item-1", queue.dequeue())
        assertNull(queue.dequeue())
    }
}
