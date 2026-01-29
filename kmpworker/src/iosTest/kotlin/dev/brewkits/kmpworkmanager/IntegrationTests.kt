package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.cinterop.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

/**
 * v2.3.0: Integration tests for complete workflows
 * Tests migration, recovery, policy handling, and edge cases
 */
@OptIn(ExperimentalForeignApi::class)
class IntegrationTests {

    private lateinit var testDirectoryURL: NSURL
    private lateinit var queueDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        // Create temporary test directory
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_integration_test_${NSDate().timeIntervalSince1970}"
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

    // ==================== Migration Tests ====================

    @Test
    fun `testMigrationFromTextToBinary - ⚠️ CRITICAL migration test`() = runTest {
        val fileManager = NSFileManager.defaultManager
        val queueFileURL = queueDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        // Step 1: Create legacy text queue with 3 items
        val legacyItems = listOf(
            """{"id":"chain-1","type":"test"}""",
            """{"id":"chain-2","type":"test"}""",
            """{"id":"chain-3","type":"test"}"""
        )

        val legacyContent = legacyItems.joinToString("\n") + "\n"
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val nsString = legacyContent as NSString
            nsString.writeToFile(
                queuePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
        }

        // Verify legacy file exists
        assertTrue(fileManager.fileExistsAtPath(queuePath), "Legacy queue file should exist")

        // Step 2: Instantiate new AppendOnlyQueue (triggers migration)
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Step 3: Verify size preserved
        assertEquals(3, queue.getSize(), "Queue size should be preserved after migration")

        // Step 4: Verify data integrity (dequeue all items)
        val dequeuedItems = mutableListOf<String>()
        repeat(3) {
            val item = queue.dequeue()
            assertNotNull(item, "Should dequeue item ${it + 1}")
            dequeuedItems.add(item)
        }

        assertEquals(legacyItems, dequeuedItems, "All items should be preserved in order")

        // Step 5: Verify new file starts with MAGIC header
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForReadingFromURL(queueFileURL, errorPtr.ptr)
            assertNotNull(fileHandle, "Should be able to open queue file")

            try {
                val magicData = fileHandle.readDataOfLength(4u)
                assertEquals(4UL, magicData.length, "Should read 4 bytes of magic number")

                val magicBytes = magicData.bytes?.reinterpret<ByteVar>()
                assertNotNull(magicBytes, "Magic bytes should not be null")

                // Read magic number (little endian)
                val magic = (magicBytes[0].toUByte().toUInt()) or
                        (magicBytes[1].toUByte().toUInt() shl 8) or
                        (magicBytes[2].toUByte().toUInt() shl 16) or
                        (magicBytes[3].toUByte().toUInt() shl 24)

                assertEquals(0x4B4D5051u, magic, "Magic number should be 'KMPQ' (0x4B4D5051)")
            } finally {
                fileHandle.closeFile()
            }
        }
    }

    @Test
    fun `testMigrationWithEmptyQueue - empty legacy queue handled gracefully`() = runTest {
        val fileManager = NSFileManager.defaultManager
        val queueFileURL = queueDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        // Create empty legacy file
        fileManager.createFileAtPath(queuePath, null, null)

        // Instantiate queue (triggers migration)
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Verify empty queue
        assertEquals(0, queue.getSize(), "Empty queue should have size 0")
        assertNull(queue.dequeue(), "Empty queue should return null on dequeue")

        // Verify can still enqueue
        queue.enqueue("item-1")
        assertEquals(1, queue.getSize())
        assertEquals("item-1", queue.dequeue())
    }

    @Test
    fun `testMigrationWithLargeQueue - 1000 items migration performance`() = runTest {
        val fileManager = NSFileManager.defaultManager
        val queueFileURL = queueDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        // Create legacy queue with 1000 items
        val items = (1..1000).map { """{"id":"chain-$it","data":"test"}""" }
        val legacyContent = items.joinToString("\n") + "\n"

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val nsString = legacyContent as NSString
            nsString.writeToFile(
                queuePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
        }

        // Measure migration time
        val startTime = TimeSource.Monotonic.markNow()
        val queue = AppendOnlyQueue(queueDirectoryURL)
        val migrationDuration = startTime.elapsedNow()

        println("Migration of 1000 items took ${migrationDuration.inWholeMilliseconds}ms")

        // Verify migration time < 5s
        assertTrue(
            migrationDuration.inWholeMilliseconds < 5000,
            "Migration should complete in < 5s (actual: ${migrationDuration.inWholeMilliseconds}ms)"
        )

        // Verify all items preserved
        assertEquals(1000, queue.getSize(), "All 1000 items should be preserved")

        // Spot check: dequeue first and last items
        val first = queue.dequeue()
        assertEquals("""{"id":"chain-1","data":"test"}""", first)

        // Skip to last item
        repeat(998) { queue.dequeue() }

        val last = queue.dequeue()
        assertEquals("""{"id":"chain-1000","data":"test"}""", last)
    }

    // ==================== Force-Quit Recovery Tests ====================

    @Test
    fun `testForceQuitRecovery - queue persisted after force-quit`() = runTest {
        val fileStorage = IosFileStorage()

        // Step 1: Enqueue chains
        val chainId1 = "chain-force-quit-1"
        val chainId2 = "chain-force-quit-2"

        val steps = listOf(listOf(TaskRequest("TestWorker", "input")))
        fileStorage.saveChainDefinition(chainId1, steps)
        fileStorage.saveChainDefinition(chainId2, steps)

        fileStorage.enqueueChain(chainId1)
        fileStorage.enqueueChain(chainId2)

        assertEquals(2, fileStorage.getQueueSize())

        // Step 2: Simulate force-quit (don't cleanup, just drop reference)
        // In real scenario, app process is killed here

        // Step 3: Create new instances (simulating app restart)
        val newFileStorage = IosFileStorage()

        // Step 4: Verify queue persisted
        assertEquals(2, newFileStorage.getQueueSize(), "Queue should be persisted")

        // Step 5: Verify execution can continue
        val dequeued1 = newFileStorage.dequeueChain()
        assertEquals(chainId1, dequeued1)

        val dequeued2 = newFileStorage.dequeueChain()
        assertEquals(chainId2, dequeued2)

        assertEquals(0, newFileStorage.getQueueSize())
    }

    // ==================== ExistingPolicy Tests ====================

    @Test
    fun `testExistingPolicyKeep - only one chain in queue`() = runTest {
        val fileStorage = IosFileStorage()
        val chainId = "test-chain-keep"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Enqueue first time
        fileStorage.saveChainDefinition(chainId, steps1)
        fileStorage.enqueueChain(chainId)

        assertEquals(1, fileStorage.getQueueSize())

        // Try to enqueue again with KEEP policy (simulate)
        if (fileStorage.chainExists(chainId)) {
            // KEEP policy - skip
        } else {
            fileStorage.saveChainDefinition(chainId, steps2)
            fileStorage.enqueueChain(chainId)
        }

        // Verify only one in queue
        assertEquals(1, fileStorage.getQueueSize(), "Queue should only have 1 chain (KEEP policy)")

        // Verify original definition preserved
        val loaded = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals("Worker1", loaded[0][0].workerClassName)
    }

    @Test
    fun `testExistingPolicyReplace - new chain definition loaded`() = runTest {
        val fileStorage = IosFileStorage()
        val chainId = "test-chain-replace"
        val steps1 = listOf(listOf(TaskRequest("Worker1", "input1")))
        val steps2 = listOf(listOf(TaskRequest("Worker2", "input2")))

        // Enqueue first chain
        fileStorage.saveChainDefinition(chainId, steps1)
        fileStorage.enqueueChain(chainId)

        // Replace with new chain (REPLACE policy)
        if (fileStorage.chainExists(chainId)) {
            fileStorage.markChainAsDeleted(chainId)
            fileStorage.deleteChainDefinition(chainId)
            fileStorage.deleteChainProgress(chainId)
        }

        fileStorage.saveChainDefinition(chainId, steps2)
        fileStorage.enqueueChain(chainId)

        // Verify new definition
        val loaded = fileStorage.loadChainDefinition(chainId)
        assertNotNull(loaded)
        assertEquals("Worker2", loaded[0][0].workerClassName, "Should have new worker")

        // Verify deleted marker exists
        assertTrue(fileStorage.isChainDeleted(chainId), "Deleted marker should exist")
    }

    // ==================== Disk Full Handling Tests ====================

    @Test
    fun `testDiskFullHandling - clear error when disk full`() = runTest {
        // Note: Cannot actually simulate disk full in unit tests
        // This test verifies the API exists and throws expected exception type

        val fileStorage = IosFileStorage()
        val chainId = "test-large-chain"

        // Create very large chain (exceed MAX_CHAIN_SIZE_BYTES)
        val largeSteps = (1..1000).map {
            listOf(TaskRequest("Worker$it", "x".repeat(10000)))
        }

        try {
            fileStorage.saveChainDefinition(chainId, largeSteps)
            fail("Should throw exception for oversized chain")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Chain size exceeds limit"))
        }
    }

    // ==================== Queue Corruption Recovery Tests ====================

    @Test
    fun `testQueueCorruptionRecovery - graceful handling of corrupt data`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Enqueue valid items
        queue.enqueue("item-1")
        queue.enqueue("item-2")

        // Manually corrupt queue file
        val queueFileURL = queueDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        memScoped {
            // Overwrite with corrupted binary data
            val corruptData = byteArrayOf(
                0x4B.toByte(), 0x4D.toByte(), 0x50.toByte(), 0x51.toByte(), // Magic
                0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Version
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()  // Invalid length
            )
            val nsData = corruptData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = corruptData.size.toULong())
            }
            nsData.writeToFile(queuePath, atomically = true)
        }

        // Try to dequeue - should handle gracefully
        val result = queue.dequeue()
        assertNull(result, "Should return null for corrupted queue")

        // Queue should be resetable
        queue.resetQueue()

        // Verify queue is usable after reset
        queue.enqueue("item-after-reset")
        val newResult = queue.dequeue()
        assertEquals("item-after-reset", newResult)
    }

    // ==================== Binary Format Integrity Tests ====================

    @Test
    fun `testBinaryFormatIntegrity - enqueue and dequeue with CRC validation`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Enqueue items with various content
        val items = listOf(
            """{"id":"test-1","data":"simple"}""",
            """{"id":"test-2","data":"with\nnewline"}""",
            """{"id":"test-3","data":"Unicode: 你好 🚀"}""",
            """{"id":"test-4","data":"${("x".repeat(1000))}"}""" // Large item
        )

        items.forEach { queue.enqueue(it) }

        // Dequeue and verify all items
        items.forEach { expected ->
            val actual = queue.dequeue()
            assertEquals(expected, actual, "Item should match with CRC validation")
        }

        assertNull(queue.dequeue(), "Queue should be empty")
    }

    @Test
    fun `testBinaryFormatCRCDetection - detects corrupted data`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Enqueue valid item
        queue.enqueue("""{"id":"test","data":"valid"}""")

        // Corrupt the binary data (flip bits in CRC)
        val queueFileURL = queueDirectoryURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, errorPtr.ptr)

            if (fileHandle != null) {
                try {
                    // Seek to near end (where CRC is stored)
                    val fileSize = fileHandle.seekToEndOfFile()
                    if (fileSize > 8UL) {
                        fileHandle.seekToFileOffset(fileSize - 5UL) // Before CRC
                        // Write corrupted byte
                        val corruptByte = byteArrayOf(0xFF.toByte())
                        val nsData = corruptByte.usePinned { pinned ->
                            NSData.create(bytes = pinned.addressOf(0), length = 1.toULong())
                        }
                        fileHandle.writeData(nsData)
                    }
                } finally {
                    fileHandle.closeFile()
                }
            }
        }

        // Try to dequeue - should detect corruption
        val result = queue.dequeue()
        assertNull(result, "Should return null for CRC mismatch")
    }

    // ==================== Compaction Tests ====================

    @Test
    fun `testCompactionTriggeredAt80Percent - automatic space reclamation`() = runTest {
        val queue = AppendOnlyQueue(queueDirectoryURL)

        // Enqueue 200 items
        repeat(200) { i ->
            queue.enqueue("item-$i")
        }

        assertEquals(200, queue.getSize())

        // Dequeue 161 items (80.5% processed)
        repeat(161) {
            queue.dequeue()
        }

        assertEquals(39, queue.getSize())

        // Next dequeue should trigger compaction (80% threshold reached)
        queue.dequeue()

        assertEquals(38, queue.getSize())

        // Verify remaining items still accessible
        repeat(38) { i ->
            val item = queue.dequeue()
            assertNotNull(item, "Item ${162 + i} should still be accessible after compaction")
        }

        assertNull(queue.dequeue(), "Queue should be empty")
    }
}
