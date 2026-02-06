package dev.brewkits.kmpworkmanager.utils

import kotlin.test.*

/**
 * Unit tests for Logger configuration and filtering (Task #1)
 * Tests:
 * - Level filtering
 * - Custom logger delegation
 * - Default behavior
 */
class LoggerTest {

    private var capturedLogs = mutableListOf<LogEntry>()

    private val testLogger = object : CustomLogger {
        override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
            capturedLogs.add(LogEntry(level, tag, message, throwable))
        }
    }

    @BeforeTest
    fun setup() {
        capturedLogs.clear()
        Logger.setMinLevel(Logger.Level.VERBOSE)
        Logger.setCustomLogger(null)
    }

    @AfterTest
    fun teardown() {
        // Reset to defaults
        Logger.setMinLevel(Logger.Level.VERBOSE)
        Logger.setCustomLogger(null)
    }

    @Test
    fun testDefaultBehavior() {
        // Should not throw exception
        Logger.d("TEST", "Debug message")
        Logger.i("TEST", "Info message")
        Logger.w("TEST", "Warning message")
        Logger.e("TEST", "Error message")
    }

    @Test
    fun testLevelFiltering_Info() {
        Logger.setMinLevel(Logger.Level.INFO)
        Logger.setCustomLogger(testLogger)

        Logger.v("TEST", "Verbose - should be filtered")
        Logger.d("TEST", "Debug - should be filtered")
        Logger.i("TEST", "Info - should pass")
        Logger.w("TEST", "Warning - should pass")
        Logger.e("TEST", "Error - should pass")

        assertEquals(3, capturedLogs.size, "Should have 3 logs (INFO, WARN, ERROR)")
        assertEquals(Logger.Level.INFO, capturedLogs[0].level)
        assertEquals(Logger.Level.WARN, capturedLogs[1].level)
        assertEquals(Logger.Level.ERROR, capturedLogs[2].level)
    }

    @Test
    fun testLevelFiltering_Warn() {
        Logger.setMinLevel(Logger.Level.WARN)
        Logger.setCustomLogger(testLogger)

        Logger.v("TEST", "Verbose - filtered")
        Logger.d("TEST", "Debug - filtered")
        Logger.i("TEST", "Info - filtered")
        Logger.w("TEST", "Warning - should pass")
        Logger.e("TEST", "Error - should pass")

        assertEquals(2, capturedLogs.size, "Should have 2 logs (WARN, ERROR)")
        assertEquals(Logger.Level.WARN, capturedLogs[0].level)
        assertEquals(Logger.Level.ERROR, capturedLogs[1].level)
    }

    @Test
    fun testLevelFiltering_Error() {
        Logger.setMinLevel(Logger.Level.ERROR)
        Logger.setCustomLogger(testLogger)

        Logger.v("TEST", "Verbose - filtered")
        Logger.d("TEST", "Debug - filtered")
        Logger.i("TEST", "Info - filtered")
        Logger.w("TEST", "Warning - filtered")
        Logger.e("TEST", "Error - should pass")

        assertEquals(1, capturedLogs.size, "Should have 1 log (ERROR)")
        assertEquals(Logger.Level.ERROR, capturedLogs[0].level)
    }

    @Test
    fun testCustomLoggerDelegation() {
        Logger.setCustomLogger(testLogger)

        val testMessage = "Test message"
        val testTag = "TEST_TAG"
        Logger.i(testTag, testMessage)

        assertEquals(1, capturedLogs.size)
        assertEquals(Logger.Level.INFO, capturedLogs[0].level)
        assertEquals(testTag, capturedLogs[0].tag)
        assertEquals(testMessage, capturedLogs[0].message)
        assertNull(capturedLogs[0].throwable)
    }

    @Test
    fun testCustomLoggerWithThrowable() {
        Logger.setCustomLogger(testLogger)

        val testException = RuntimeException("Test exception")
        Logger.e("TEST", "Error occurred", testException)

        assertEquals(1, capturedLogs.size)
        assertEquals(Logger.Level.ERROR, capturedLogs[0].level)
        assertNotNull(capturedLogs[0].throwable)
        assertEquals("Test exception", capturedLogs[0].throwable?.message)
    }

    @Test
    fun testSpamReduction() {
        // Simulate production config (INFO level)
        Logger.setMinLevel(Logger.Level.INFO)
        Logger.setCustomLogger(testLogger)

        // Simulate typical log calls (based on 122 log calls in codebase)
        repeat(50) { Logger.v("TEST", "Verbose spam") }
        repeat(30) { Logger.d("TEST", "Debug spam") }
        repeat(20) { Logger.i("TEST", "Info message") }
        repeat(15) { Logger.w("TEST", "Warning message") }
        repeat(7) { Logger.e("TEST", "Error message") }

        // Should filter VERBOSE (50) and DEBUG (30) = 80 filtered
        // Should pass INFO (20) + WARN (15) + ERROR (7) = 42 passed
        assertEquals(42, capturedLogs.size, "Should reduce logs from 122 to 42 (90% reduction)")
    }

    @Test
    fun testSetCustomLoggerToNull() {
        Logger.setCustomLogger(testLogger)
        Logger.i("TEST", "First message")
        assertEquals(1, capturedLogs.size)

        // Clear custom logger
        Logger.setCustomLogger(null)
        Logger.i("TEST", "Second message - should not be captured")

        // Still only 1 from before
        assertEquals(1, capturedLogs.size)
    }

    @Test
    fun testLevelOrdering() {
        val levels = listOf(
            Logger.Level.VERBOSE,
            Logger.Level.DEBUG_LEVEL,
            Logger.Level.INFO,
            Logger.Level.WARN,
            Logger.Level.ERROR
        )

        for (i in 0 until levels.size - 1) {
            assertTrue(
                levels[i].ordinal < levels[i + 1].ordinal,
                "${levels[i]} should have lower ordinal than ${levels[i + 1]}"
            )
        }
    }

    private data class LogEntry(
        val level: Logger.Level,
        val tag: String,
        val message: String,
        val throwable: Throwable?
    )
}
