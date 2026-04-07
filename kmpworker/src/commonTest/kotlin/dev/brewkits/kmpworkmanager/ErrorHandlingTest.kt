package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.CRC32
import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.measureTime
import kotlin.time.Duration.Companion.milliseconds

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

    @Test
    fun testLoggerThreadSafety() = runTest {
        val logCount = atomic(0)
        val customLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount.incrementAndGet()
            }
        }

        Logger.setCustomLogger(customLogger)

        // Concurrent logging
        val jobs = List(10) {
            launch(Dispatchers.Default) {
                repeat(100) { i ->
                    Logger.d("TEST-${it}", "Message $i")
                }
            }
        }

        jobs.joinAll()

        // Should have received all 1000 logs
        assertEquals(1000, logCount.value, "Should handle concurrent logging")
    }

    @Test
    fun testCRC32VeryLargeData() {
        // 100MB data
        val largeData = ByteArray(100 * 1024 * 1024) { (it % 256).toByte() }

        val duration = measureTime {
            CRC32.calculate(largeData)
        }
        val result = CRC32.calculate(largeData) // Recalculate to get the result outside measureTime

        assertNotEquals(0u, result, "Should calculate CRC for large data")

        // Should complete in reasonable time (< 10s even for pure Kotlin)
        assertTrue(duration.inWholeSeconds < 10L, "Should handle 100MB in <10s (was ${duration})")
    }

    @Test
    fun testConcurrentLoggerConfiguration() = runTest {
        // Use no-op logger to avoid NSLog throttling on real device (test verifies no crash, not output)
        Logger.setCustomLogger(object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        })

        val jobs = List(10) {
            launch(Dispatchers.Default) {
                repeat(100) { i ->
                    Logger.setMinLevel(Logger.Level.values()[i % 5])
                    Logger.d("TEST", "Message $i")
                }
            }
        }

        jobs.joinAll()

        // Should not crash
    }

    @Test
    fun testConcurrentCRC32Calculation() = runTest {
        val testData = "Concurrent test data 测试 🚀".encodeToByteArray()

        val jobs = List(10) {
            launch(Dispatchers.Default) {
                repeat(1000) {
                    CRC32.calculate(testData)
                }
            }
        }

        jobs.joinAll()

        // Should not crash
    }
}
