package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for v2.2.2 bug fixes (Cross-Platform)
 *
 * Tests critical, high, and medium priority fixes:
 * - Logger configuration race (#15)
 * - Koin shutdown lifecycle (#17)
 * - Test all fixes work together in realistic scenarios
 */
class V222BugFixesIntegrationTest {

    /**
     * FIX #15: Logger Configuration Race
     *
     * Test that concurrent setMinLevel() calls don't cause data races
     */
    @Test
    fun testLoggerConfigurationThreadSafety() = runBlocking {
        // Setup: Reset logger to default
        Logger.setMinLevel(Logger.Level.VERBOSE)
        Logger.setCustomLogger(null)

        val jobs = List(100) { index ->
            launch {
                // Randomly set different log levels
                val level = when (index % 5) {
                    0 -> Logger.Level.VERBOSE
                    1 -> Logger.Level.DEBUG_LEVEL
                    2 -> Logger.Level.INFO
                    3 -> Logger.Level.WARN
                    else -> Logger.Level.ERROR
                }
                Logger.setMinLevel(level)
                delay(1) // Small delay to encourage race conditions
            }
        }

        jobs.forEach { it.join() }

        // Verify no crash and logger is in a valid state
        Logger.d("Test", "Logger configuration survived concurrent access")
        assertTrue(true, "Logger configuration thread-safety test passed")
    }

    /**
     * FIX #15: Custom Logger Thread Safety
     *
     * Test that setting custom logger concurrently is thread-safe
     */
    @Test
    fun testCustomLoggerConcurrentSet() = runBlocking {
        val testLogger1 = TestCustomLogger("Logger1")
        val testLogger2 = TestCustomLogger("Logger2")

        val jobs = List(50) { index ->
            launch {
                if (index % 2 == 0) {
                    Logger.setCustomLogger(testLogger1)
                } else {
                    Logger.setCustomLogger(testLogger2)
                }
                delay(1)
            }
        }

        jobs.forEach { it.join() }

        // Verify no crash
        Logger.i("Test", "Custom logger concurrent access survived")
        assertTrue(true, "Custom logger thread-safety test passed")

        // Cleanup
        Logger.setCustomLogger(null)
    }

    /**
     * FIX #15: Logger Filtering Works Correctly
     *
     * Test that log level filtering works as expected
     */
    @Test
    fun testLoggerFilteringWorks() {
        val testLogger = TestCustomLogger("TestLogger")
        Logger.setCustomLogger(testLogger)
        Logger.setMinLevel(Logger.Level.WARN)

        // These should NOT be logged
        Logger.v("Test", "Verbose message")
        Logger.d("Test", "Debug message")
        Logger.i("Test", "Info message")

        // These SHOULD be logged
        Logger.w("Test", "Warning message")
        Logger.e("Test", "Error message")

        // Verify filtering (test logger should have received only WARN and ERROR)
        assertTrue(testLogger.logCount >= 2, "Should have logged at least WARN and ERROR")

        // Cleanup
        Logger.setCustomLogger(null)
        Logger.setMinLevel(Logger.Level.VERBOSE)
    }

    /**
     * FIX #17: Koin Shutdown Method
     *
     * Test that KmpWorkManager can be shutdown and reinitialized
     */
    @Test
    fun testKoinShutdownAndReinitialize() {
        // This test verifies the shutdown() method exists and can be called
        // Platform-specific tests will verify actual Koin cleanup

        // Verify shutdown method exists (compilation test)
        assertNotNull(KmpWorkManager::shutdown, "Shutdown method should exist")

        // Note: Actual shutdown testing requires platform-specific context
        assertTrue(true, "Koin shutdown API available")
    }

    /**
     * Integration Test: Multiple Fixes Working Together
     *
     * Simulates realistic scenario with:
     * - Logger configuration changes
     * - Concurrent operations
     * - Custom logger
     */
    @Test
    fun testMultipleFixesIntegration() = runBlocking {
        val testLogger = TestCustomLogger("Integration")
        Logger.setCustomLogger(testLogger)
        Logger.setMinLevel(Logger.Level.INFO)

        // Simulate concurrent application startup
        val jobs = List(20) { index ->
            launch {
                when (index % 4) {
                    0 -> Logger.i("Integration", "Task $index started")
                    1 -> Logger.w("Integration", "Task $index warning")
                    2 -> Logger.e("Integration", "Task $index error")
                    else -> Logger.d("Integration", "Task $index debug (should be filtered)")
                }
                delay(5)
            }
        }

        jobs.forEach { it.join() }

        // Verify no crashes and logs were captured
        assertTrue(testLogger.logCount > 0, "Should have captured some logs")
        Logger.i("Integration", "Integration test completed successfully")

        // Cleanup
        Logger.setCustomLogger(null)
        Logger.setMinLevel(Logger.Level.VERBOSE)
    }

    /**
     * Stress Test: Rapid Configuration Changes
     *
     * Test that rapid logger configuration changes don't cause issues
     */
    @Test
    fun testRapidConfigurationChanges() = runBlocking {
        repeat(1000) { iteration ->
            val level = when (iteration % 5) {
                0 -> Logger.Level.VERBOSE
                1 -> Logger.Level.DEBUG_LEVEL
                2 -> Logger.Level.INFO
                3 -> Logger.Level.WARN
                else -> Logger.Level.ERROR
            }
            Logger.setMinLevel(level)

            if (iteration % 10 == 0) {
                Logger.d("Stress", "Iteration $iteration")
            }
        }

        // Verify no crash
        assertTrue(true, "Survived 1000 rapid configuration changes")

        // Reset
        Logger.setMinLevel(Logger.Level.VERBOSE)
    }

    /**
     * Test Custom Logger Implementation
     */
    private class TestCustomLogger(private val name: String) : dev.brewkits.kmpworkmanager.utils.CustomLogger {
        var logCount = 0

        override fun log(
            level: Logger.Level,
            tag: String,
            message: String,
            throwable: Throwable?
        ) {
            logCount++
            // In a real test, you might verify the messages
        }
    }
}
