package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlin.test.*

/**
 * Comprehensive tests for KmpWorkManagerConfig
 * Tests configuration validation, defaults, and integration
 */
class KmpWorkManagerConfigTest {

    @Test
    fun testDefaultConfiguration() {
        val config = KmpWorkManagerConfig()

        assertEquals(Logger.Level.INFO, config.logLevel, "Default log level should be INFO")
        assertNull(config.customLogger, "Default custom logger should be null")
    }

    @Test
    fun testCustomLogLevel() {
        val levels = listOf(
            Logger.Level.VERBOSE,
            Logger.Level.DEBUG,
            Logger.Level.INFO,
            Logger.Level.WARN,
            Logger.Level.ERROR
        )

        levels.forEach { level ->
            val config = KmpWorkManagerConfig(logLevel = level)
            assertEquals(level, config.logLevel, "Should accept log level $level")
        }
    }

    @Test
    fun testCustomLoggerConfiguration() {
        val customLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                // No-op
            }
        }

        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = customLogger
        )

        assertEquals(Logger.Level.DEBUG, config.logLevel)
        assertSame(customLogger, config.customLogger)
    }

    @Test
    fun testConfigImmutability() {
        val logger1 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config1 = KmpWorkManagerConfig(
            logLevel = Logger.Level.INFO,
            customLogger = logger1
        )

        // Create new config with different values
        val logger2 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config2 = KmpWorkManagerConfig(
            logLevel = Logger.Level.WARN,
            customLogger = logger2
        )

        // Original config should be unchanged
        assertEquals(Logger.Level.INFO, config1.logLevel)
        assertSame(logger1, config1.customLogger)

        // New config should have new values
        assertEquals(Logger.Level.WARN, config2.logLevel)
        assertSame(logger2, config2.customLogger)
    }

    @Test
    fun testConfigCopy() {
        val originalLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val original = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = originalLogger
        )

        // Copy with same values
        val copy = original.copy()

        assertEquals(original.logLevel, copy.logLevel)
        assertSame(original.customLogger, copy.customLogger)
    }

    @Test
    fun testConfigCopyWithChanges() {
        val logger1 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val original = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = logger1
        )

        // Copy with changed log level
        val copy1 = original.copy(logLevel = Logger.Level.WARN)
        assertEquals(Logger.Level.WARN, copy1.logLevel)
        assertSame(logger1, copy1.customLogger)

        // Copy with changed logger
        val logger2 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }
        val copy2 = original.copy(customLogger = logger2)
        assertEquals(Logger.Level.DEBUG, copy2.logLevel)
        assertSame(logger2, copy2.customLogger)

        // Copy with all changes
        val copy3 = original.copy(logLevel = Logger.Level.ERROR, customLogger = null)
        assertEquals(Logger.Level.ERROR, copy3.logLevel)
        assertNull(copy3.customLogger)
    }

    @Test
    fun testConfigEquality() {
        val logger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config1 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger)
        val config2 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger)

        // Data classes have structural equality
        assertEquals(config1, config2)
    }

    @Test
    fun testConfigInequality() {
        val logger1 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }
        val logger2 = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config1 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger1)
        val config2 = KmpWorkManagerConfig(logLevel = Logger.Level.WARN, customLogger = logger1)
        val config3 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger2)

        assertNotEquals(config1, config2, "Different log levels should not be equal")
        assertNotEquals(config1, config3, "Different loggers should not be equal")
    }

    @Test
    fun testConfigToString() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = null
        )

        val string = config.toString()

        assertTrue(string.contains("logLevel"), "toString should include logLevel")
        assertTrue(string.contains("DEBUG"), "toString should include log level value")
    }

    @Test
    fun testConfigHashCode() {
        val logger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config1 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger)
        val config2 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger)

        // Equal objects should have same hash code
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun testConfigWithNullLogger() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.ERROR,
            customLogger = null
        )

        assertEquals(Logger.Level.ERROR, config.logLevel)
        assertNull(config.customLogger, "Should handle null custom logger")
    }

    @Test
    fun testConfigProductionSettings() {
        // Production configuration
        val productionConfig = KmpWorkManagerConfig(
            logLevel = Logger.Level.WARN, // Only warnings and errors
            customLogger = null
        )

        assertEquals(Logger.Level.WARN, productionConfig.logLevel)
        assertNull(productionConfig.customLogger)
    }

    @Test
    fun testConfigDevelopmentSettings() {
        // Development configuration
        val devConfig = KmpWorkManagerConfig(
            logLevel = Logger.Level.VERBOSE, // All logs
            customLogger = object : CustomLogger {
                override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                    println("[$level] $tag: $message")
                }
            }
        )

        assertEquals(Logger.Level.VERBOSE, devConfig.logLevel)
        assertNotNull(devConfig.customLogger)
    }

    @Test
    fun testConfigStagingSettings() {
        // Staging configuration
        val stagingConfig = KmpWorkManagerConfig(
            logLevel = Logger.Level.INFO, // Info, warnings, and errors
            customLogger = null
        )

        assertEquals(Logger.Level.INFO, stagingConfig.logLevel)
        assertNull(stagingConfig.customLogger)
    }

    @Test
    fun testMultipleConfigsIndependent() {
        val logger1 = object : CustomLogger {
            var logCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount++
            }
        }

        val logger2 = object : CustomLogger {
            var logCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount++
            }
        }

        val config1 = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG, customLogger = logger1)
        val config2 = KmpWorkManagerConfig(logLevel = Logger.Level.INFO, customLogger = logger2)

        // Verify configs are independent
        assertEquals(Logger.Level.DEBUG, config1.logLevel)
        assertEquals(Logger.Level.INFO, config2.logLevel)
        assertSame(logger1, config1.customLogger)
        assertSame(logger2, config2.customLogger)
    }

    @Test
    fun testConfigLoggerInvocation() {
        var invocationCount = 0
        val testLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                invocationCount++
            }
        }

        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = testLogger
        )

        // Apply config to Logger
        Logger.setMinLevel(config.logLevel)
        Logger.setCustomLogger(config.customLogger)

        // Test logging
        Logger.d("TEST", "Message 1")
        Logger.i("TEST", "Message 2")
        Logger.w("TEST", "Message 3")

        assertEquals(3, invocationCount, "Custom logger should receive all logs")
    }

    @Test
    fun testConfigLoggerFiltering() {
        val capturedLogs = mutableListOf<Logger.Level>()
        val testLogger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                capturedLogs.add(level)
            }
        }

        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.WARN,
            customLogger = testLogger
        )

        // Apply config
        Logger.setMinLevel(config.logLevel)
        Logger.setCustomLogger(config.customLogger)

        // Test logging at various levels
        Logger.v("TEST", "Verbose")
        Logger.d("TEST", "Debug")
        Logger.i("TEST", "Info")
        Logger.w("TEST", "Warning")
        Logger.e("TEST", "Error")

        // Only WARN and ERROR should be captured
        assertEquals(2, capturedLogs.size, "Should only capture WARN and ERROR")
        assertEquals(Logger.Level.WARN, capturedLogs[0])
        assertEquals(Logger.Level.ERROR, capturedLogs[1])
    }

    @Test
    fun testConfigDestructuring() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.DEBUG,
            customLogger = null
        )

        val (logLevel, customLogger) = config

        assertEquals(Logger.Level.DEBUG, logLevel)
        assertNull(customLogger)
    }

    @Test
    fun testConfigComponentAccess() {
        val logger = object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {}
        }

        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.WARN,
            customLogger = logger
        )

        // Access via component functions (data class feature)
        assertEquals(Logger.Level.WARN, config.component1())
        assertSame(logger, config.component2())
    }

    @Test
    fun testConfigValidation() {
        // All log levels should be valid
        Logger.Level.values().forEach { level ->
            val config = KmpWorkManagerConfig(logLevel = level)
            assertNotNull(config, "Should accept level $level")
        }
    }
}
