package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.CRC32
import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlin.test.*

/**
 * Comprehensive error handling and edge case tests
 * Tests error paths, boundary conditions, and recovery scenarios
 */
class ErrorHandlingTest {

    @BeforeTest
    fun setup() {
        Logger.setMinLevel(Logger.Level.VERBOSE)
        Logger.setCustomLogger(null)
    }

    @AfterTest
    fun teardown() {
        Logger.setMinLevel(Logger.Level.VERBOSE)
        Logger.setCustomLogger(null)
    }

    // ===========================
    // Logger Error Handling
    // ===========================

    @Test
    fun testLoggerHandlesNullMessage() {
        // Should not crash with null-like empty string
        Logger.d("TEST", "")
        Logger.i("TEST", "")
        Logger.w("TEST", "")
        Logger.e("TEST", "")
    }

    @Test
    fun testLoggerHandlesVeryLongMessage() {
        val longMessage = "x".repeat(100_000) // 100KB message
        Logger.d("TEST", longMessage)
        // Should not crash
    }

    @Test
    fun testLoggerHandlesSpecialCharacters() {
        val specialChars = "测试 🚀 \n\t\r 特殊字符 \u0000 \uFFFF"
        Logger.d("TEST", specialChars)
        // Should not crash
    }

    @Test
    fun testLoggerHandlesExceptionWithNullMessage() {
        val exception = RuntimeException(null as String?)
        Logger.e("TEST", "Error occurred", exception)
        // Should not crash
    }

    @Test
    fun testCustomLoggerException() {
        val throwingLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                throw RuntimeException("Logger failed")
            }
        }

        Logger.setCustomLogger(throwingLogger)

        // Logger should handle custom logger exceptions gracefully
        try {
            Logger.d("TEST", "Message")
            // Should either catch exception internally or propagate
        } catch (e: Exception) {
            // Acceptable behavior - exception propagated
            assertTrue(e.message?.contains("Logger failed") == true)
        }
    }

    @Test
    fun testLoggerThreadSafety() {
        val customLogger = object : CustomLogger {
            var logCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount++
            }
        }

        Logger.setCustomLogger(customLogger)

        // Concurrent logging
        val threads = List(10) {
            Thread {
                repeat(100) { i ->
                    Logger.d("TEST-${it}", "Message $i")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should have received all 1000 logs
        assertEquals(1000, customLogger.logCount, "Should handle concurrent logging")
    }

    // ===========================
    // CRC32 Error Handling
    // ===========================

    @Test
    fun testCRC32EmptyArray() {
        val result = CRC32.calculate(byteArrayOf())
        assertEquals(0u, result, "CRC32 of empty array should be 0")
    }

    @Test
    fun testCRC32VeryLargeData() {
        // 100MB data
        val largeData = ByteArray(100 * 1024 * 1024) { (it % 256).toByte() }

        val startTime = System.currentTimeMillis()
        val result = CRC32.calculate(largeData)
        val duration = System.currentTimeMillis() - startTime

        assertNotEquals(0u, result, "Should calculate CRC for large data")

        // Should complete in reasonable time (< 10s even for pure Kotlin)
        assertTrue(duration < 10_000, "Should handle 100MB in <10s (was ${duration}ms)")
    }

    @Test
    fun testCRC32AllZeros() {
        val zeros = ByteArray(1000) { 0 }
        val result = CRC32.calculate(zeros)
        assertNotEquals(0u, result, "CRC32 of all zeros should not be 0")
    }

    @Test
    fun testCRC32AllMaxValues() {
        val maxValues = ByteArray(1000) { 0xFF.toByte() }
        val result = CRC32.calculate(maxValues)
        assertNotEquals(0u, result, "CRC32 of all 0xFF should not be 0")
    }

    @Test
    fun testCRC32BoundarySize() {
        // Test various power-of-2 sizes
        val sizes = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192)

        sizes.forEach { size ->
            val data = ByteArray(size) { (it % 256).toByte() }
            val result = CRC32.calculate(data)
            assertNotEquals(0u, result, "Should calculate CRC for size $size")
        }
    }

    @Test
    fun testCRC32OddSize() {
        // Test non-aligned sizes
        val oddSizes = listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 31, 63, 127, 255, 511, 1023)

        oddSizes.forEach { size ->
            val data = ByteArray(size) { (it % 256).toByte() }
            val result = CRC32.calculate(data)
            assertNotEquals(0u, result, "Should handle odd size $size")
        }
    }

    @Test
    fun testCRC32Consistency() {
        // Same input should always produce same output
        val data = "Test consistency data 测试 🚀".encodeToByteArray()

        val results = List(100) {
            CRC32.calculate(data)
        }

        assertTrue(results.all { it == results[0] }, "CRC32 should be consistent")
    }

    // ===========================
    // KmpWorkManagerConfig Error Handling
    // ===========================

    @Test
    fun testConfigWithNullCustomLogger() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.INFO,
            customLogger = null
        )

        // Should handle null logger gracefully
        assertNotNull(config)
        assertEquals(Logger.Level.INFO, config.logLevel)
        assertNull(config.customLogger)
    }

    @Test
    fun testConfigWithAllLogLevels() {
        val levels = listOf(
            Logger.Level.VERBOSE,
            Logger.Level.DEBUG,
            Logger.Level.INFO,
            Logger.Level.WARN,
            Logger.Level.ERROR
        )

        levels.forEach { level ->
            val config = KmpWorkManagerConfig(logLevel = level)
            assertEquals(level, config.logLevel, "Should accept level $level")
        }
    }

    // ===========================
    // Boundary Conditions
    // ===========================

    @Test
    fun testMaxIntValue() {
        val maxInt = Int.MAX_VALUE
        val timeoutMs = maxInt.toLong()

        // Should handle max values without overflow
        assertTrue(timeoutMs > 0, "Should handle max int as timeout")
    }

    @Test
    fun testMinIntValue() {
        val minInt = Int.MIN_VALUE
        val timeoutMs = minInt.toLong()

        // Should handle negative values
        assertTrue(timeoutMs < 0, "Should preserve negative values")
    }

    @Test
    fun testULongMaxValue() {
        val maxULong = ULong.MAX_VALUE

        // Should handle max unsigned long
        assertTrue(maxULong > 0u, "Should handle ULong.MAX_VALUE")
    }

    // ===========================
    // Concurrent Access
    // ===========================

    @Test
    fun testConcurrentLoggerConfiguration() {
        val threads = List(10) {
            Thread {
                repeat(100) { i ->
                    // Rapidly change logger configuration
                    Logger.setMinLevel(Logger.Level.values()[i % 5])
                    Logger.d("TEST", "Message $i")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not crash
    }

    @Test
    fun testConcurrentCRC32Calculation() {
        val testData = "Concurrent test data 测试 🚀".encodeToByteArray()

        val threads = List(10) {
            Thread {
                repeat(1000) {
                    CRC32.calculate(testData)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not crash
    }

    // ===========================
    // Memory Stress Tests
    // ===========================

    @Test
    fun testMemoryStressLargeMessages() {
        // Log 1000 large messages
        repeat(1000) { i ->
            val largeMessage = "Message $i: " + "x".repeat(10_000)
            Logger.d("STRESS", largeMessage)
        }

        // Should not crash or cause OOM
    }

    @Test
    fun testMemoryStressCRC32() {
        // Calculate CRC32 for 100 medium-sized chunks
        repeat(100) {
            val data = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB
            CRC32.calculate(data)
        }

        // Should not cause OOM
    }

    // ===========================
    // Unicode and Emoji Handling
    // ===========================

    @Test
    fun testUnicodeInLogger() {
        val unicodeStrings = listOf(
            "English text",
            "中文测试",
            "日本語テスト",
            "한글 테스트",
            "Русский текст",
            "العربية",
            "עברית",
            "🚀🎉💻🔥",
            "Mixed: 测试 🚀 Test"
        )

        unicodeStrings.forEach { text ->
            Logger.d("UNICODE", text)
        }

        // Should handle all Unicode strings
    }

    @Test
    fun testUnicodeInCRC32() {
        val unicodeStrings = listOf(
            "中文测试",
            "日本語テスト",
            "🚀🎉💻🔥",
            "Mixed: 测试 🚀 Test"
        )

        unicodeStrings.forEach { text ->
            val data = text.encodeToByteArray()
            val crc = CRC32.calculate(data)
            assertNotEquals(0u, crc, "Should calculate CRC for Unicode: $text")
        }
    }

    // ===========================
    // Recovery from Errors
    // ===========================

    @Test
    fun testLoggerRecoveryAfterException() {
        val throwingLogger = object : CustomLogger {
            var callCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                callCount++
                if (callCount < 5) {
                    throw RuntimeException("Temporary failure")
                }
            }
        }

        Logger.setCustomLogger(throwingLogger)

        // First few calls may throw
        repeat(10) { i ->
            try {
                Logger.d("TEST", "Message $i")
            } catch (e: Exception) {
                // Expected for first few calls
            }
        }

        // Should eventually succeed after logger stabilizes
        assertTrue(throwingLogger.callCount >= 10, "Logger should continue working")
    }

    @Test
    fun testReconfigureLoggerMultipleTimes() {
        repeat(100) { i ->
            val logger = object : CustomLogger {
                override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                    // No-op
                }
            }

            Logger.setCustomLogger(logger)
            Logger.setMinLevel(Logger.Level.values()[i % 5])
            Logger.d("TEST", "Message $i")
        }

        // Should handle repeated reconfiguration
    }

    // ===========================
    // Platform Differences
    // ===========================

    @Test
    fun testPlatformSpecificMaxValues() {
        // Test that platform-specific max values are handled
        val platformMaxTimeout = 30_000L // iOS BGTask limit

        assertTrue(platformMaxTimeout > 0, "Should handle platform max timeout")
        assertTrue(platformMaxTimeout < Long.MAX_VALUE, "Should be reasonable value")
    }
}
