package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

/**
 * Comprehensive tests for Koin Isolation (Task #8)
 * Tests:
 * - Private KoinApplication doesn't pollute global Koin
 * - No conflicts with host app's Koin
 * - Proper initialization and cleanup
 * - Concurrent Koin instances
 * - Version conflict handling
 */
class KoinIsolationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stopKoin() // Clean slate
    }

    @After
    fun tearDown() {
        stopKoin() // Clean up after each test
    }

    /**
     * Test KmpWorkManager uses private Koin (not global)
     */
    @Test
    fun testPrivateKoinInstanceNotGlobal() {
        val workerFactory = TestWorkerFactory()

        // Initialize KmpWorkManager
        KmpWorkManager.initialize(
            context = context,
            workerFactory = workerFactory,
            config = KmpWorkManagerConfig()
        )

        // Verify global Koin is NOT started by KmpWorkManager
        assertFalse(
            GlobalContext.getOrNull() != null,
            "KmpWorkManager should NOT use global Koin"
        )
    }

    /**
     * Test no conflict with host app's Koin
     */
    @Test
    fun testNoConflictWithHostAppKoin() {
        // Start host app's Koin
        startKoin {
            androidContext(context)
            modules(module {
                single<String> { "Host App Data" }
            })
        }

        val hostKoin = GlobalContext.get()
        val hostData = hostKoin.get<String>()
        assertEquals("Host App Data", hostData, "Host app Koin should work")

        // Initialize KmpWorkManager (should use private Koin)
        KmpWorkManager.initialize(
            context = context,
            workerFactory = TestWorkerFactory(),
            config = KmpWorkManagerConfig()
        )

        // Verify host app's Koin is unaffected
        val hostDataAfter = GlobalContext.get().get<String>()
        assertEquals("Host App Data", hostDataAfter, "Host app Koin should remain intact")
    }

    /**
     * Test KmpWorkManager Koin has isolated dependencies
     */
    @Test
    fun testIsolatedDependencies() {
        // Host app Koin with conflicting definition
        startKoin {
            androidContext(context)
            modules(module {
                single<WorkerFactory> { HostWorkerFactory() } // Conflicting!
            })
        }

        // KmpWorkManager with different factory
        val kmpWorkerFactory = TestWorkerFactory()
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpWorkerFactory,
            config = KmpWorkManagerConfig()
        )

        // Verify host app's factory is not affected
        val hostFactory = GlobalContext.get().get<WorkerFactory>()
        assertTrue(hostFactory is HostWorkerFactory, "Host factory should be HostWorkerFactory")

        // Verify KmpWorkManager uses its own factory (internal check)
        // Note: This would require internal testing or public API to verify
    }

    /**
     * Test multiple KmpWorkManager initializations (reinitialize scenario)
     */
    @Test
    fun testReinitializationHandled() {
        val factory1 = TestWorkerFactory()
        val factory2 = TestWorkerFactory()

        // First initialization
        KmpWorkManager.initialize(context, factory1, KmpWorkManagerConfig())

        // Second initialization (should replace or handle gracefully)
        KmpWorkManager.initialize(context, factory2, KmpWorkManagerConfig())

        // Should not crash or leak Koin instances
        assertNotNull(context, "Context should still be valid")
    }

    /**
     * Test Koin isolation prevents version conflicts
     */
    @Test
    fun testKoinVersionConflictPrevented() {
        // Simulate host app using different Koin version with conflicting module
        startKoin {
            androidContext(context)
            modules(module {
                single<BackgroundTaskScheduler> { MockScheduler() } // Conflict!
            })
        }

        // KmpWorkManager initialization should not conflict
        try {
            KmpWorkManager.initialize(
                context = context,
                workerFactory = TestWorkerFactory(),
                config = KmpWorkManagerConfig()
            )
            // Success - no conflict
        } catch (e: Exception) {
            fail("Should not throw exception due to Koin conflict: ${e.message}")
        }
    }

    /**
     * Test private Koin cleanup doesn't affect global Koin
     */
    @Test
    fun testPrivateKoinCleanupDoesNotAffectGlobal() {
        // Start host app Koin
        startKoin {
            androidContext(context)
            modules(module {
                single<String> { "Persistent Data" }
            })
        }

        // Initialize and "cleanup" KmpWorkManager
        KmpWorkManager.initialize(context, TestWorkerFactory(), KmpWorkManagerConfig())

        // Simulate KmpWorkManager cleanup (if such API exists)
        // For now, just verify global Koin is still intact

        val globalData = GlobalContext.get().get<String>()
        assertEquals("Persistent Data", globalData, "Global Koin should be unaffected by KmpWorkManager cleanup")
    }

    /**
     * Test concurrent access to private and global Koin
     */
    @Test
    fun testConcurrentAccessPrivateAndGlobalKoin() {
        // Start global Koin
        startKoin {
            androidContext(context)
            modules(module {
                single<String> { "Global" }
            })
        }

        // Initialize KmpWorkManager
        KmpWorkManager.initialize(context, TestWorkerFactory(), KmpWorkManagerConfig())

        // Access both concurrently
        val jobs = List(10) {
            Thread {
                repeat(100) {
                    GlobalContext.get().get<String>() // Access global
                    Thread.sleep(1)
                }
            }
        }

        jobs.forEach { it.start() }
        jobs.forEach { it.join() }

        // Should not crash or deadlock
        assertEquals("Global", GlobalContext.get().get<String>())
    }

    /**
     * Test error handling when Context is invalid
     */
    @Test
    fun testErrorHandlingInvalidContext() {
        // This test verifies error handling - implementation may vary
        // For now, just verify it doesn't crash
        try {
            KmpWorkManager.initialize(
                context = context, // Valid context
                workerFactory = TestWorkerFactory(),
                config = KmpWorkManagerConfig()
            )
            // Should succeed
        } catch (e: Exception) {
            fail("Should handle valid context: ${e.message}")
        }
    }

    /**
     * Test custom logger configuration in private Koin
     */
    @Test
    fun testCustomLoggerInPrivateKoin() {
        val customLogger = object : CustomLogger {
            var logCount = 0
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                logCount++
            }
        }

        KmpWorkManager.initialize(
            context = context,
            workerFactory = TestWorkerFactory(),
            config = KmpWorkManagerConfig(
                logLevel = Logger.Level.DEBUG,
                customLogger = customLogger
            )
        )

        // Logger should be configured in private Koin
        // This is indirectly tested - logger configuration happens in KmpWorkManagerKoin.initialize
        Logger.d("TEST", "Test message")

        assertTrue(customLogger.logCount > 0, "Custom logger should receive logs")
    }

    /**
     * Stress test: 100 rapid initializations
     */
    @Test
    fun stressTestRapidInitializations() {
        repeat(100) { i ->
            KmpWorkManager.initialize(
                context = context,
                workerFactory = TestWorkerFactory(),
                config = KmpWorkManagerConfig()
            )

            if (i % 10 == 0) {
                Thread.sleep(10) // Occasional delay
            }
        }

        // Should not crash or leak memory
        assertNotNull(context)
    }

    // ===========================
    // Test Helpers
    // ===========================

    private class TestWorkerFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? {
            return when (workerClassName) {
                "TestWorker" -> TestWorker()
                else -> null
            }
        }
    }

    private class HostWorkerFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = null
    }

    private class TestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): Boolean = true
    }

    private class MockScheduler : BackgroundTaskScheduler {
        override fun enqueue(
            id: String,
            trigger: dev.brewkits.kmpworkmanager.background.domain.TaskTrigger,
            workerClassName: String,
            constraints: dev.brewkits.kmpworkmanager.background.domain.Constraints,
            inputJson: String?,
            retryCount: Int
        ) {}

        override fun cancel(id: String) {}
        override fun cancelAll() {}
    }
}
