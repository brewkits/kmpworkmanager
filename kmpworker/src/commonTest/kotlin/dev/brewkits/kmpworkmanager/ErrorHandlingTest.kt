package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.CRC32
import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*
import kotlinx.datetime.Clock
import kotlin.time.measureTime
import kotlin.time.Duration.Companion.milliseconds

    @Test
    fun testLoggerThreadSafety() = runBlocking {
        val customLogger = object : CustomLogger {
            var logCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount++
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
        assertEquals(1000, customLogger.logCount, "Should handle concurrent logging")
    }

    // ...

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
        assertTrue(duration < 10.milliseconds * 1000, "Should handle 100MB in <10s (was ${duration})")
    }

    // ...

    @Test
    fun testConcurrentLoggerConfiguration() = runBlocking {
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
    fun testConcurrentCRC32Calculation() = runBlocking {
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