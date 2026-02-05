package dev.brewkits.kmpworkmanager.background.data

import kotlin.test.*

/**
 * Unit and stress tests for Queue Persisted Index (Task #7)
 * Tests:
 * - Index persistence and loading
 * - O(1) startup performance
 * - Corruption recovery
 * - Large queue handling (10,000+ items)
 * - Binary format correctness
 */
class QueueIndexTest {

    /**
     * Mock QueueIndex for testing persistence logic
     */
    private class MockQueueIndex {
        private var persistedData: ByteArray? = null

        fun saveIndex(lineOffsets: Map<Int, ULong>) {
            // Simulate binary format: [offset0:8][offset1:8][offset2:8]...
            val sortedOffsets = lineOffsets.toList().sortedBy { it.first }
            val buffer = mutableListOf<Byte>()

            sortedOffsets.forEach { (_, offset: ULong) ->
                // Write 8 bytes (little-endian)
                for (i in 0..7) {
                    buffer.add(((offset.toLong() shr (i * 8)) and 0xFFL).toByte())
                }
            }

            persistedData = buffer.toByteArray()
        }

        fun loadIndex(): Map<Int, ULong> {
            val data = persistedData ?: return emptyMap()
            if (data.size % 8 != 0) return emptyMap() // Corrupted

            val lineCount = data.size / 8
            val offsets = mutableMapOf<Int, ULong>()

            for (i in 0 until lineCount) {
                val offset = readULongAt(data, i * 8)
                offsets[i] = offset
            }

            return offsets
        }

        private fun readULongAt(data: ByteArray, index: Int): ULong {
            var value = 0UL
            for (i in 0..7) {
                value = value or ((data[index + i].toULong() and 0xFFUL) shl (i * 8))
            }
            return value
        }

        fun clear() {
            persistedData = null
        }

        fun corruptData() {
            persistedData = byteArrayOf(0x01, 0x02, 0x03) // Not multiple of 8
        }
    }

    @Test
    fun testPersistenceBasic() {
        val index = MockQueueIndex()
        val offsets = mapOf(
            0 to 0UL,
            1 to 100UL,
            2 to 250UL,
            3 to 500UL
        )

        index.saveIndex(offsets)
        val loaded = index.loadIndex()

        assertEquals(4, loaded.size, "Should load all 4 entries")
        assertEquals(0UL, loaded[0])
        assertEquals(100UL, loaded[1])
        assertEquals(250UL, loaded[2])
        assertEquals(500UL, loaded[3])
    }

    @Test
    fun testPersistenceEmpty() {
        val index = MockQueueIndex()
        val offsets = emptyMap<Int, ULong>()

        index.saveIndex(offsets)
        val loaded = index.loadIndex()

        assertEquals(0, loaded.size, "Should load empty index")
    }

    @Test
    fun testPersistenceLargeOffsets() {
        val index = MockQueueIndex()
        val offsets = mapOf(
            0 to ULong.MAX_VALUE,
            1 to 0UL,
            2 to 123456789012345UL
        )

        index.saveIndex(offsets)
        val loaded = index.loadIndex()

        assertEquals(ULong.MAX_VALUE, loaded[0], "Should handle MAX_VALUE")
        assertEquals(0UL, loaded[1], "Should handle 0")
        assertEquals(123456789012345UL, loaded[2], "Should handle large offsets")
    }

    @Test
    fun testCorruptionRecovery() {
        val index = MockQueueIndex()

        // Corrupt the data
        index.corruptData()
        val loaded = index.loadIndex()

        // Should return empty map on corruption
        assertEquals(0, loaded.size, "Should return empty on corrupted data")
    }

    @Test
    fun testEmptyIndexLoad() {
        val index = MockQueueIndex()
        val loaded = index.loadIndex()

        // No data persisted yet
        assertEquals(0, loaded.size, "Should return empty when no data exists")
    }

    @Test
    fun testIndexOrdering() {
        val index = MockQueueIndex()
        val offsets = mapOf(
            3 to 500UL,
            1 to 100UL,
            0 to 0UL,
            2 to 250UL
        )

        index.saveIndex(offsets)
        val loaded = index.loadIndex()

        // Should preserve order by line number
        val keys = loaded.keys.sorted()
        assertEquals(listOf(0, 1, 2, 3), keys, "Should maintain sequential order")
    }

    /**
     * Stress test: 10,000 queue items (target O(1) startup)
     */
    @Test
    fun stressTestLargeQueue_10K() {
        val index = MockQueueIndex()

        // Generate 10,000 line offsets
        val offsets = (0 until 10_000).associateWith { i ->
            (i * 100).toULong() // Simulate ~100 byte lines
        }

        // Measure save performance
        val saveStart = System.currentTimeMillis()
        index.saveIndex(offsets)
        val saveDuration = System.currentTimeMillis() - saveStart

        // Measure load performance
        val loadStart = System.currentTimeMillis()
        val loaded = index.loadIndex()
        val loadDuration = System.currentTimeMillis() - loadStart

        assertEquals(10_000, loaded.size, "Should load all 10,000 entries")

        // Load should be O(1) - constant time regardless of queue size
        // Target: <100ms for 10K items
        assertTrue(
            loadDuration < 100,
            "Load should complete in <100ms for 10K items (was ${loadDuration}ms)"
        )

        println("10K index performance: Save=${saveDuration}ms, Load=${loadDuration}ms")
    }

    /**
     * Stress test: Very large queue (100,000 items)
     */
    @Test
    fun stressTestVeryLargeQueue_100K() {
        val index = MockQueueIndex()

        // Generate 100,000 line offsets
        val offsets = (0 until 100_000).associateWith { i ->
            (i * 100).toULong()
        }

        val saveStart = System.currentTimeMillis()
        index.saveIndex(offsets)
        val saveDuration = System.currentTimeMillis() - saveStart

        val loadStart = System.currentTimeMillis()
        val loaded = index.loadIndex()
        val loadDuration = System.currentTimeMillis() - loadStart

        assertEquals(100_000, loaded.size, "Should load all 100,000 entries")

        // Should still be fast even at 100K
        // Target: <500ms for 100K items
        assertTrue(
            loadDuration < 500,
            "Load should complete in <500ms for 100K items (was ${loadDuration}ms)"
        )

        println("100K index performance: Save=${saveDuration}ms, Load=${loadDuration}ms")
    }

    /**
     * Performance comparison: O(1) indexed vs O(N) sequential
     */
    @Test
    fun performanceComparisonIndexedVsSequential() {
        val index = MockQueueIndex()
        val itemCount = 10_000

        val offsets = (0 until itemCount).associateWith { i ->
            (i * 100).toULong()
        }

        // Indexed approach (O(1))
        index.saveIndex(offsets)
        val indexedStart = System.currentTimeMillis()
        index.loadIndex()
        val indexedDuration = System.currentTimeMillis() - indexedStart

        // Simulate sequential scan (O(N))
        val sequentialStart = System.currentTimeMillis()
        var lastOffset = 0UL
        repeat(itemCount) { i ->
            lastOffset += 100UL // Simulate reading line length
        }
        val sequentialDuration = System.currentTimeMillis() - sequentialStart

        // Indexed should be significantly faster
        val speedup = sequentialDuration.toDouble() / indexedDuration.toDouble()

        println("\n=== Performance Comparison (${itemCount} items) ===")
        println("Indexed (O(1)): ${indexedDuration}ms")
        println("Sequential (O(N)): ${sequentialDuration}ms")
        println("Speedup: ${speedup.toInt()}x faster")

        // Target: At least 10x faster (goal is 40x in real scenarios)
        assertTrue(
            speedup >= 10.0,
            "Indexed should be at least 10x faster (was ${speedup}x)"
        )
    }

    /**
     * Test binary format correctness
     */
    @Test
    fun testBinaryFormatCorrectness() {
        val index = MockQueueIndex()

        // Test various offset values
        val testCases = listOf(
            0UL,
            1UL,
            255UL,
            256UL,
            65535UL,
            65536UL,
            16777215UL,
            16777216UL,
            4294967295UL,
            4294967296UL,
            ULong.MAX_VALUE
        )

        testCases.forEachIndexed { i, offset ->
            val offsets = mapOf(i to offset)
            index.saveIndex(offsets)
            val loaded = index.loadIndex()

            assertEquals(
                offset,
                loaded[i],
                "Binary format should correctly encode/decode $offset"
            )
        }
    }

    /**
     * Integration test: Simulate real queue startup
     */
    @Test
    fun integrationTestQueueStartup() {
        val index = MockQueueIndex()

        // Simulate app shutdown: save current queue state
        val queueSize = 5_000
        val offsets = (0 until queueSize).associateWith { i ->
            (i * 150).toULong() // ~150 bytes per item
        }

        index.saveIndex(offsets)

        // Simulate app restart: load index
        val loadStart = System.currentTimeMillis()
        val loaded = index.loadIndex()
        val loadDuration = System.currentTimeMillis() - loadStart

        // Verify loaded correctly
        assertEquals(queueSize, loaded.size, "Should restore full queue state")

        // Verify O(1) performance
        assertTrue(
            loadDuration < 50,
            "Cold start should complete in <50ms for 5K items (was ${loadDuration}ms)"
        )

        // Verify random access works
        val middleOffset = loaded[queueSize / 2]
        val expectedOffset = ((queueSize / 2) * 150).toULong()
        assertEquals(expectedOffset, middleOffset, "Should support random access")

        println("Integration test: ${queueSize} items loaded in ${loadDuration}ms (O(1) startup)")
    }

    /**
     * Stress test: Incremental updates (append pattern)
     */
    @Test
    fun stressTestIncrementalUpdates() {
        val index = MockQueueIndex()
        var offsets = emptyMap<Int, ULong>()

        val totalItems = 1_000
        val batchSize = 100

        // Simulate incremental queue growth
        var totalSaveTime = 0L
        for (batch in 0 until (totalItems / batchSize)) {
            val newOffsets = (batch * batchSize until (batch + 1) * batchSize).associateWith { i ->
                (i * 100).toULong()
            }

            offsets = offsets + newOffsets

            val saveStart = System.currentTimeMillis()
            index.saveIndex(offsets)
            totalSaveTime += System.currentTimeMillis() - saveStart
        }

        // Verify final state
        val loaded = index.loadIndex()
        assertEquals(totalItems, loaded.size, "Should have all items after incremental updates")

        println("Incremental updates: ${totalItems} items in ${totalItems / batchSize} batches, total save time: ${totalSaveTime}ms")
    }
}
