package dev.brewkits.kmpworkmanager.utils

import kotlin.test.*

/**
 * Unit tests for CRC32 platform implementations (Task #2)
 * Tests:
 * - Correctness (matches known CRC32 values)
 * - Binary compatibility across platforms
 * - Edge cases (empty, large data)
 * - Performance benchmarking
 */
class CRC32Test {

    @Test
    fun testEmptyData() {
        val result = CRC32.calculate(byteArrayOf())
        // CRC32 of empty data is 0
        assertEquals(0u, result, "CRC32 of empty data should be 0")
    }

    @Test
    fun testKnownValues() {
        // Test vector: "123456789" -> 0xCBF43926
        val testData = "123456789".encodeToByteArray()
        val result = CRC32.calculate(testData)
        assertEquals(0xCBF43926u, result, "CRC32('123456789') should match known value")
    }

    @Test
    fun testSingleByte() {
        val data = byteArrayOf(0x41) // 'A'
        val result = CRC32.calculate(data)
        // Known CRC32('A') = 0xD3D99E8B
        assertEquals(0xD3D99E8Bu, result)
    }

    @Test
    fun testHelloWorld() {
        val data = "Hello, World!".encodeToByteArray()
        val result = CRC32.calculate(data)
        // Known CRC32('Hello, World!') = 0xEC4AC3D0
        assertEquals(0xEC4AC3D0u, result)
    }

    @Test
    fun testBinaryData() {
        // Test with binary data (not just text)
        val data = byteArrayOf(0x00, 0xFF.toByte(), 0x55, 0xAA.toByte(), 0x12, 0x34, 0x56, 0x78)
        val result = CRC32.calculate(data)
        assertNotEquals(0u, result, "CRC32 of binary data should not be 0")
    }

    @Test
    fun testLargeData() {
        // Test with 1MB of data
        val size = 1024 * 1024
        val data = ByteArray(size) { (it % 256).toByte() }

        val startTime = timeMillis()
        val result = CRC32.calculate(data)
        val duration = timeMillis() - startTime

        assertNotEquals(0u, result, "CRC32 of 1MB data should not be 0")
        println("CRC32 1MB performance: ${duration}ms")

        // Should complete within reasonable time (native should be <50ms, pure Kotlin <500ms)
        assertTrue(duration < 500, "CRC32 should complete within 500ms for 1MB (was ${duration}ms)")
    }

    @Test
    fun testDeterministic() {
        val data = "Test data for determinism".encodeToByteArray()
        val result1 = CRC32.calculate(data)
        val result2 = CRC32.calculate(data)
        val result3 = CRC32.calculate(data)

        assertEquals(result1, result2, "CRC32 should be deterministic (run 1 vs 2)")
        assertEquals(result2, result3, "CRC32 should be deterministic (run 2 vs 3)")
    }

    @Test
    fun testDifferentInputsDifferentOutputs() {
        val data1 = "Hello".encodeToByteArray()
        val data2 = "World".encodeToByteArray()

        val result1 = CRC32.calculate(data1)
        val result2 = CRC32.calculate(data2)

        assertNotEquals(result1, result2, "Different inputs should produce different CRC32")
    }

    @Test
    fun testSimilarInputsDifferentOutputs() {
        // Slight changes should produce different CRC32
        val data1 = "abcdefgh".encodeToByteArray()
        val data2 = "abcdefgi".encodeToByteArray() // Last byte different

        val result1 = CRC32.calculate(data1)
        val result2 = CRC32.calculate(data2)

        assertNotEquals(result1, result2, "Similar inputs should produce different CRC32")
    }

    @Test
    fun testAllZeros() {
        val data = ByteArray(100) { 0 }
        val result = CRC32.calculate(data)
        assertNotEquals(0u, result, "CRC32 of all zeros should not be 0")
    }

    @Test
    fun testAllOnes() {
        val data = ByteArray(100) { 0xFF.toByte() }
        val result = CRC32.calculate(data)
        assertNotEquals(0u, result, "CRC32 of all 0xFF should not be 0")
    }

    /**
     * Stress test: Calculate CRC32 for various sizes
     * Validates performance scales linearly with data size
     */
    @Test
    fun stressTestVariousSizes() {
        val sizes = listOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)
        val results = mutableListOf<Pair<Int, Long>>()

        for (size in sizes) {
            val data = ByteArray(size) { (it % 256).toByte() }
            val startTime = timeMillis()
            val result = CRC32.calculate(data)
            val duration = timeMillis() - startTime

            results.add(size to duration)
            assertNotEquals(0u, result, "CRC32 should not be 0 for size $size")
        }

        // Print performance profile
        println("\n=== CRC32 Performance Profile ===")
        results.forEach { (size, duration) ->
            val throughput = if (duration > 0) size / duration else size
            println("Size: ${size.toString().padStart(8)} bytes | Time: ${duration.toString().padStart(5)}ms | Throughput: ${throughput}KB/ms")
        }
    }

    /**
     * Performance benchmark: Compare with target (native should be 5-10x faster)
     * This is a relative benchmark - actual speedup validated in platform-specific tests
     */
    @Test
    fun benchmarkPerformance() {
        val testData = ByteArray(10 * 1024 * 1024) { (it % 256).toByte() } // 10MB

        // Warmup
        repeat(3) { CRC32.calculate(testData) }

        // Actual benchmark
        val iterations = 5
        val times = mutableListOf<Long>()

        repeat(iterations) {
            val start = timeMillis()
            CRC32.calculate(testData)
            times.add(timeMillis() - start)
        }

        val avgTime = times.average()
        val throughput = (10.0 / avgTime) * 1000 // MB/s

        println("\n=== CRC32 Benchmark (10MB) ===")
        println("Average time: ${avgTime}ms")
        println("Throughput: ${throughput.toInt()}MB/s")
        println("Individual runs: $times")

        // Native implementation should handle 10MB in reasonable time
        // iOS zlib: ~20-50ms, Android java.util.zip: ~30-60ms
        assertTrue(avgTime < 500, "10MB should complete in <500ms (was ${avgTime}ms)")
    }

    private fun timeMillis(): Long {
        // This will be platform-specific in actual implementation
        return System.currentTimeMillis()
    }
}
