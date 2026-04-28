@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.*
import kotlin.test.*

/**
 * Clean and Robust Branch Coverage Test for iOS.
 * Focuses on isolated instances to avoid cross-test interference.
 */
class IosBranchCoverageTest {

    private val testDir = NSTemporaryDirectory() + "kmp_branch_test_" + NSDate().timeIntervalSince1970.toLong()

    @BeforeTest
    fun setup() {
        NSFileManager.defaultManager.createDirectoryAtPath(testDir, true, null, null)
    }

    @AfterTest
    fun teardown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    @Test
    fun testQueueCorruptionBranch() = kotlinx.coroutines.test.runTest {
        val queueDir = NSURL.fileURLWithPath("$testDir/corrupt_queue")
        NSFileManager.defaultManager.createDirectoryAtURL(queueDir, true, null, null)
        
        // 1. Use instance 1 to write data
        var queue: AppendOnlyQueue? = AppendOnlyQueue(queueDir)
        queue!!.enqueue("valid-item-1")
        queue = null // Release instance 1 completely

        // 2. Corrupt the file directly on disk
        val queueFileURL = queueDir.URLByAppendingPathComponent("queue.jsonl")!!
        val garbage = "CORRUPT_BYTES_HERE".encodeToByteArray()
        val data = NSData.create(bytes = garbage.usePinned { it.addressOf(0) }, length = garbage.size.toULong())
        
        val fileHandle = NSFileHandle.fileHandleForWritingToURL(queueFileURL, null)
        fileHandle?.seekToFileOffset(8u) 
        fileHandle?.writeData(data)
        fileHandle?.closeFile()

        // 3. Use a fresh instance 2 to read
        val recoveryQueue = AppendOnlyQueue(queueDir)

        // First dequeue: hits CRC error, logs it, and marks the queue corrupt internally
        val firstAttempt = recoveryQueue.dequeue()
        assertNull(firstAttempt, "First attempt: must return null on corrupt record")

        // Second dequeue: isQueueCorrupt = true branch fires, resets the file
        val secondAttempt = recoveryQueue.dequeue()
        assertNull(secondAttempt, "Second attempt: must return null after corrupt file is reset")

        // 4. Verify full recovery
        recoveryQueue.enqueue("recovered-item")
        val recovered = recoveryQueue.dequeue()
        assertEquals("recovered-item", recovered, "Queue must fully recover and allow read/write after reset")
    }

    @Test
    fun testQueueCompactionThresholdBranch() = kotlinx.coroutines.test.runTest {
        val queueDir = NSURL.fileURLWithPath("$testDir/compact_queue")
        NSFileManager.defaultManager.createDirectoryAtURL(queueDir, true, null, null)
        
        val queue = AppendOnlyQueue(queueDir)
        for (i in 1..5) queue.enqueue("item-$i")
        for (i in 1..4) queue.dequeue()
        
        val size = queue.getSize()
        assertEquals(1, size)
        
        val headPointer = NSString.stringWithContentsOfFile("$testDir/compact_queue/head_pointer.txt", NSUTF8StringEncoding, null)
        assertNotNull(headPointer)
    }

    @Test
    fun testCoordinatorAtMostOnceExecution() {
        val fileURL = NSURL.fileURLWithPath("$testDir/atomic_test.txt")
        "content".writeToURL(fileURL)

        val callCount = kotlin.concurrent.AtomicInt(0)
        IosFileCoordinator.coordinateSync<Unit>(fileURL, write = false) {
            callCount.addAndGet(1)
        }
        
        assertEquals(1, callCount.value)
    }

    private fun String.writeToURL(url: NSURL) {
        (this as NSString).writeToURL(url, true, NSUTF8StringEncoding, null)
    }
}
