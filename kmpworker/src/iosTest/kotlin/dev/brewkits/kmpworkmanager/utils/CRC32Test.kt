package dev.brewkits.kmpworkmanager.utils

import kotlin.test.*
import kotlin.time.TimeSource

/**
 * Tests correctness, Unicode support, performance, and edge cases
 */
class CRC32Test {

    // ==================== Test Vectors (Known CRC32 values) ====================

    @Test
    fun `testVector1 - empty string`() {
        val input = ""
        val expected = 0x00000000u

        val actual = CRC32.calculate(input)
        assertEquals(expected, actual, "CRC32 of empty string should be 0x00000000")
    }

    @Test
    fun `testVector2 - single byte a`() {
        val input = "a"
        val expected = 0xE8B7BE43u

        val actual = CRC32.calculate(input)
        assertEquals(expected, actual, "CRC32 of 'a' should be 0xE8B7BE43")
    }

    @Test
    fun `testVector3 - hello world`() {
        val input = "hello world"
        val expected = 0x0D4A1185u

        val actual = CRC32.calculate(input)
        assertEquals(expected, actual, "CRC32 of 'hello world' should be 0x0D4A1185")
    }

    @Test
    fun `testVector4 - 123456789`() {
        val input = "123456789"
        val expected = 0xCBF43926u

        val actual = CRC32.calculate(input)
        assertEquals(expected, actual, "CRC32 of '123456789' should be 0xCBF43926")
    }

    @Test
    fun `testVector5 - The quick brown fox`() {
        val input = "The quick brown fox jumps over the lazy dog"
        val expected = 0x414FA339u

        val actual = CRC32.calculate(input)
        assertEquals(expected, actual, "CRC32 of pangram should be 0x414FA339")
    }

    // ==================== Unicode Support Tests ====================

    @Test
    fun `testUnicode - Vietnamese text`() {
        val input = "Xin chào thế giới!"
        val crc = CRC32.calculate(input)

        // Verify it produces a consistent checksum
        val crc2 = CRC32.calculate(input)
        assertEquals(crc, crc2, "Same input should produce same CRC")

        // Verify it's different from ASCII version
        val asciiVersion = "Xin chao the gioi!"
        val asciiCrc = CRC32.calculate(asciiVersion)
        assertNotEquals(crc, asciiCrc, "Unicode and ASCII versions should have different CRCs")
    }

    @Test
    fun `testUnicode - Chinese text`() {
        val input = "你好世界！这是一个测试。"
        val crc = CRC32.calculate(input)

        // Verify consistency
        val crc2 = CRC32.calculate(input)
        assertEquals(crc, crc2, "Same Chinese input should produce same CRC")

        // Should not be zero
        assertNotEquals(0u, crc, "CRC should not be zero for valid data")
    }

    @Test
    fun `testUnicode - Emoji`() {
        val input = "🚀 🎉 🔥 ✨ 💯 ❤️"
        val crc = CRC32.calculate(input)

        // Verify consistency
        val crc2 = CRC32.calculate(input)
        assertEquals(crc, crc2, "Same emoji input should produce same CRC")

        // Different emoji should produce different CRC
        val input2 = "🌟 ⭐ 🎊 🎈"
        val crc3 = CRC32.calculate(input2)
        assertNotEquals(crc, crc3, "Different emoji should have different CRCs")
    }

    @Test
    fun `testUnicode - Mixed scripts`() {
        val input = "Hello 世界 Здравствуй مرحبا שָׁלוֹם"
        val crc = CRC32.calculate(input)

        // Verify consistency
        val crc2 = CRC32.calculate(input)
        assertEquals(crc, crc2, "Mixed scripts should produce consistent CRC")
    }

    // ==================== Verification Tests ====================

    @Test
    fun `testVerify - correct checksum`() {
        val data = "test data"
        val crc = CRC32.calculate(data)

        assertTrue(CRC32.verify(data, crc), "Verification should pass for correct checksum")
    }

    @Test
    fun `testVerify - incorrect checksum`() {
        val data = "test data"
        val wrongCrc = 0x12345678u

        assertFalse(CRC32.verify(data, wrongCrc), "Verification should fail for incorrect checksum")
    }

    @Test
    fun `testVerify - data corruption detection`() {
        val originalData = "important data"
        val crc = CRC32.calculate(originalData)

        val corruptedData = "important date" // typo: data -> date
        assertFalse(CRC32.verify(corruptedData, crc), "Should detect single character corruption")
    }

    // ==================== Extension Function Tests ====================

    @Test
    fun `testExtensions - ByteArray crc32`() {
        val data = "test".encodeToByteArray()
        val expected = CRC32.calculate(data)

        val actual = data.crc32()
        assertEquals(expected, actual, "ByteArray.crc32() should match CRC32.calculate()")
    }

    @Test
    fun `testExtensions - String crc32`() {
        val data = "test"
        val expected = CRC32.calculate(data)

        val actual = data.crc32()
        assertEquals(expected, actual, "String.crc32() should match CRC32.calculate()")
    }

    @Test
    fun `testExtensions - ByteArray verifyCrc32`() {
        val data = "test".encodeToByteArray()
        val crc = data.crc32()

        assertTrue(data.verifyCrc32(crc), "ByteArray.verifyCrc32() should work correctly")
        assertFalse(data.verifyCrc32(0u), "ByteArray.verifyCrc32() should detect mismatch")
    }

    @Test
    fun `testExtensions - String verifyCrc32`() {
        val data = "test"
        val crc = data.crc32()

        assertTrue(data.verifyCrc32(crc), "String.verifyCrc32() should work correctly")
        assertFalse(data.verifyCrc32(0u), "String.verifyCrc32() should detect mismatch")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `testEdgeCase - all zeros`() {
        val data = ByteArray(100) { 0 }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "All zeros should produce consistent CRC")

        // Should not be zero (due to initial/final XOR)
        assertNotEquals(0u, crc, "CRC of all zeros should not be zero")
    }

    @Test
    fun `testEdgeCase - all ones`() {
        val data = ByteArray(100) { 0xFF.toByte() }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "All ones should produce consistent CRC")
    }

    @Test
    fun `testEdgeCase - alternating bits`() {
        val data = ByteArray(100) { i -> if (i % 2 == 0) 0xAA.toByte() else 0x55.toByte() }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "Alternating bits should produce consistent CRC")
    }

    // ==================== Large Data Tests ====================

    @Test
    fun `testLargeData - 1KB`() {
        val data = ByteArray(1024) { it.toByte() }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "1KB data should produce consistent CRC")
    }

    @Test
    fun `testLargeData - 10KB`() {
        val data = ByteArray(10_240) { it.toByte() }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "10KB data should produce consistent CRC")
    }

    @Test
    fun `testLargeData - 100KB`() {
        val data = ByteArray(102_400) { it.toByte() }
        val crc = CRC32.calculate(data)

        // Verify consistency
        val crc2 = CRC32.calculate(data)
        assertEquals(crc, crc2, "100KB data should produce consistent CRC")
    }

    // ==================== Performance Tests ====================

    @Test
    fun `testPerformance - 1MB data`() {
        val data = ByteArray(1_048_576) { it.toByte() }

        val startTime = TimeSource.Monotonic.markNow()
        val crc = CRC32.calculate(data)
        val duration = startTime.elapsedNow()

        println("CRC32 of 1MB: ${crc.toString(16)} in ${duration.inWholeMilliseconds}ms")

        // Performance requirement: should complete in < 50ms on modern iOS devices
        // (this is a loose requirement, actual performance is much better)
        assertTrue(duration.inWholeMilliseconds < 100, "CRC32 should be fast (< 100ms for 1MB)")
    }

    @Test
    fun `testPerformance - repeated calculations`() {
        val data = "test data for performance".encodeToByteArray()

        val startTime = TimeSource.Monotonic.markNow()
        repeat(10_000) {
            CRC32.calculate(data)
        }
        val duration = startTime.elapsedNow()

        println("10,000 CRC32 calculations in ${duration.inWholeMilliseconds}ms")

        // Should be very fast for small data
        assertTrue(duration.inWholeMilliseconds < 1000, "10K calculations should be < 1s")
    }

    // ==================== Collision Resistance (Informal) ====================

    @Test
    fun `testCollisionResistance - similar strings`() {
        val checksums = mutableSetOf<UInt>()

        // Generate CRCs for similar strings
        val base = "test"
        for (i in 0..100) {
            val variant = "$base$i"
            val crc = CRC32.calculate(variant)
            checksums.add(crc)
        }

        // All should be unique (no collisions in this small set)
        assertEquals(101, checksums.size, "No collisions expected in 101 similar strings")
    }

    // ==================== JSON Data Tests (Real Use Case) ====================

    @Test
    fun `testJSON - simple object`() {
        val json = """{"id":"chain-123","status":"pending"}"""
        val crc = CRC32.calculate(json)

        // Verify consistency
        val crc2 = CRC32.calculate(json)
        assertEquals(crc, crc2, "JSON should produce consistent CRC")

        // Different JSON should have different CRC
        val json2 = """{"id":"chain-124","status":"pending"}"""
        val crc3 = CRC32.calculate(json2)
        assertNotEquals(crc, crc3, "Different JSON should have different CRC")
    }

    @Test
    fun `testJSON - complex nested`() {
        val json = """
            {
                "chainId": "abc-123",
                "steps": [
                    {"worker": "SyncWorker", "input": {"url": "https://api.example.com"}},
                    {"worker": "ProcessWorker", "input": {"mode": "fast"}}
                ],
                "metadata": {
                    "timestamp": 1234567890,
                    "priority": "high"
                }
            }
        """.trimIndent()

        val crc = CRC32.calculate(json)

        // Verify consistency
        val crc2 = CRC32.calculate(json)
        assertEquals(crc, crc2, "Complex JSON should produce consistent CRC")

        // Whitespace changes should produce different CRC
        val json2 = json.replace(" ", "")
        val crc3 = CRC32.calculate(json2)
        assertNotEquals(crc, crc3, "Whitespace changes should affect CRC")
    }
}
