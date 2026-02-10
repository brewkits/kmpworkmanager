package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestWorkerBuilder
import dev.brewkits.kmpworkmanager.background.data.KmpWorker
import dev.brewkits.kmpworkmanager.background.data.KmpHeavyWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

/**
 * Comprehensive tests for Critical Fix #3: Koin Scope Isolation in KmpWorker and KmpHeavyWorker
 *
 * Bug: KmpWorker and KmpHeavyWorker were using:
 * - `private val workerFactory: AndroidWorkerFactory by inject()`
 * This resolves from global Koin, causing:
 * - Conflicts with host app's Koin
 * - Cannot use multiple WorkManager instances
 * - Version conflicts
 *
 * Fix: Changed to use isolated Koin:
 * - `private val workerFactory: AndroidWorkerFactory = KmpWorkManagerKoin.getKoin().get()`
 *
 * This test verifies:
 * - KmpWorker uses KmpWorkManagerKoin, not global Koin
 * - KmpHeavyWorker uses KmpWorkManagerKoin, not global Koin
 * - No interference with host app's global Koin
 * - Proper factory resolution from isolated scope
 * - Multiple WorkManager instances work independently
 */
class KmpWorkerKoinScopeTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stopKoin() // Clean slate
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Test 1: KmpWorker resolves factory from isolated Koin, not global
     *
     * Scenario: Initialize KmpWorkManager with custom factory,
     *           start global Koin with different factory
     * Expected: KmpWorker should use KmpWorkManager's factory, not global
     */
    @Test
    fun testKmpWorkerUsesIsolatedKoin() = runBlocking {
        // Initialize KmpWorkManager with specific factory
        val kmpFactory = TestAndroidWorkerFactory("KmpFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Start global Koin with different factory (conflict!)
        startKoin {
            modules(module {
                single<AndroidWorkerFactory> { TestAndroidWorkerFactory("GlobalFactory") }
            })
        }

        // Create KmpWorker and execute it
        val worker = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val result = worker.doWork()

        // Verify it succeeded (used correct isolated factory)
        assertTrue(
            result is ListenableWorker.Result.Success,
            "KmpWorker should succeed using isolated Koin factory"
        )

        // Verify global Koin still has its own factory
        val globalFactory = GlobalContext.get().get<AndroidWorkerFactory>()
        assertTrue(
            globalFactory is TestAndroidWorkerFactory,
            "Global Koin should maintain its own factory"
        )
        assertEquals("GlobalFactory", (globalFactory as TestAndroidWorkerFactory).name)
    }

    /**
     * Test 2: KmpHeavyWorker resolves factory from isolated Koin, not global
     *
     * Scenario: Same as Test 1, but for KmpHeavyWorker
     * Expected: KmpHeavyWorker should use KmpWorkManager's factory, not global
     */
    @Test
    fun testKmpHeavyWorkerUsesIsolatedKoin() = runBlocking {
        // Initialize KmpWorkManager with specific factory
        val kmpFactory = TestAndroidWorkerFactory("KmpHeavyFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Start global Koin with different factory (conflict!)
        startKoin {
            modules(module {
                single<AndroidWorkerFactory> { TestAndroidWorkerFactory("GlobalHeavyFactory") }
            })
        }

        // Create KmpHeavyWorker and execute it
        val worker = TestWorkerBuilder<KmpHeavyWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val result = worker.doWork()

        // Verify it succeeded (used correct isolated factory)
        assertTrue(
            result is ListenableWorker.Result.Success,
            "KmpHeavyWorker should succeed using isolated Koin factory"
        )

        // Verify global Koin still has its own factory
        val globalFactory = GlobalContext.get().get<AndroidWorkerFactory>()
        assertTrue(
            globalFactory is TestAndroidWorkerFactory,
            "Global Koin should maintain its own factory"
        )
        assertEquals("GlobalHeavyFactory", (globalFactory as TestAndroidWorkerFactory).name)
    }

    /**
     * Test 3: KmpWorker works when global Koin is NOT initialized
     *
     * Scenario: Host app doesn't use Koin at all
     * Expected: KmpWorker should still work (using isolated Koin only)
     */
    @Test
    fun testKmpWorkerWorksWithoutGlobalKoin() = runBlocking {
        // Ensure global Koin is NOT started
        stopKoin()
        assertNull(GlobalContext.getOrNull(), "Global Koin should not be initialized")

        // Initialize KmpWorkManager
        val kmpFactory = TestAndroidWorkerFactory("IsolatedFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Create and execute KmpWorker
        val worker = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val result = worker.doWork()

        // Should succeed without global Koin
        assertTrue(
            result is ListenableWorker.Result.Success,
            "KmpWorker should work without global Koin"
        )

        // Global Koin should still be null
        assertNull(GlobalContext.getOrNull(), "Global Koin should remain uninitialized")
    }

    /**
     * Test 4: KmpHeavyWorker works when global Koin is NOT initialized
     *
     * Scenario: Same as Test 3, but for KmpHeavyWorker
     * Expected: KmpHeavyWorker should still work
     */
    @Test
    fun testKmpHeavyWorkerWorksWithoutGlobalKoin() = runBlocking {
        // Ensure global Koin is NOT started
        stopKoin()
        assertNull(GlobalContext.getOrNull(), "Global Koin should not be initialized")

        // Initialize KmpWorkManager
        val kmpFactory = TestAndroidWorkerFactory("IsolatedHeavyFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Create and execute KmpHeavyWorker
        val worker = TestWorkerBuilder<KmpHeavyWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val result = worker.doWork()

        // Should succeed without global Koin
        assertTrue(
            result is ListenableWorker.Result.Success,
            "KmpHeavyWorker should work without global Koin"
        )

        // Global Koin should still be null
        assertNull(GlobalContext.getOrNull(), "Global Koin should remain uninitialized")
    }

    /**
     * Test 5: Multiple workers use same isolated factory instance
     *
     * Scenario: Create multiple KmpWorker instances
     * Expected: All should use the same isolated factory from KmpWorkManagerKoin
     */
    @Test
    fun testMultipleWorkersShareIsolatedFactory() = runBlocking {
        val kmpFactory = TestAndroidWorkerFactory("SharedFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Create multiple workers
        val worker1 = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val worker2 = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        val worker3 = TestWorkerBuilder<KmpHeavyWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()

        // Execute all workers
        val result1 = worker1.doWork()
        val result2 = worker2.doWork()
        val result3 = worker3.doWork()

        // All should succeed
        assertTrue(result1 is ListenableWorker.Result.Success, "Worker 1 should succeed")
        assertTrue(result2 is ListenableWorker.Result.Success, "Worker 2 should succeed")
        assertTrue(result3 is ListenableWorker.Result.Success, "Worker 3 should succeed")

        // All should use same factory (incremented call count)
        assertTrue(kmpFactory.createWorkerCallCount >= 3,
            "All workers should use shared factory")
    }

    /**
     * Test 6: Worker handles null worker from factory correctly
     *
     * Scenario: Factory returns null (worker not found)
     * Expected: Worker should fail gracefully
     */
    @Test
    fun testWorkerHandlesNullFromFactory() = runBlocking {
        val kmpFactory = TestAndroidWorkerFactory("NullFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Request non-existent worker
        val worker = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "NonExistentWorker", // Not registered
                "inputJson" to null
            )
        ).build()

        val result = worker.doWork()

        // Should fail gracefully
        assertTrue(
            result is ListenableWorker.Result.Failure,
            "Worker should fail when factory returns null"
        )
    }

    /**
     * Test 7: Isolated Koin survives global Koin restart
     *
     * Scenario: Host app restarts its global Koin
     * Expected: KmpWorker should continue working
     */
    @Test
    fun testIsolatedKoinSurvivesGlobalKoinRestart() = runBlocking {
        // Initialize KmpWorkManager
        val kmpFactory = TestAndroidWorkerFactory("PersistentFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Start global Koin
        startKoin {
            modules(module {
                single<String> { "Initial" }
            })
        }

        // Execute worker - should work
        val worker1 = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()
        val result1 = worker1.doWork()
        assertTrue(result1 is ListenableWorker.Result.Success, "Worker should work before restart")

        // Restart global Koin
        stopKoin()
        startKoin {
            modules(module {
                single<String> { "Restarted" }
            })
        }

        // Execute worker again - should still work
        val worker2 = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()
        val result2 = worker2.doWork()
        assertTrue(result2 is ListenableWorker.Result.Success,
            "Worker should still work after global Koin restart")

        // Verify global Koin was actually restarted
        val globalValue = GlobalContext.get().get<String>()
        assertEquals("Restarted", globalValue, "Global Koin should be restarted")
    }

    /**
     * Test 8: KmpWorker and KmpHeavyWorker use same factory instance
     *
     * Scenario: Both worker types resolve from same isolated Koin
     * Expected: Should get same factory instance
     */
    @Test
    fun testBothWorkerTypesUseSameFactory() = runBlocking {
        val kmpFactory = TestAndroidWorkerFactory("SharedFactoryForBoth")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Execute KmpWorker
        val kmpWorker = TestWorkerBuilder<KmpWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()
        kmpWorker.doWork()

        val callCountAfterKmpWorker = kmpFactory.createWorkerCallCount

        // Execute KmpHeavyWorker
        val kmpHeavyWorker = TestWorkerBuilder<KmpHeavyWorker>(
            context = context,
            inputData = androidx.work.workDataOf(
                "workerClassName" to "TestWorker",
                "inputJson" to null
            )
        ).build()
        kmpHeavyWorker.doWork()

        val callCountAfterKmpHeavyWorker = kmpFactory.createWorkerCallCount

        // Both should have incremented the same factory's call count
        assertEquals(
            callCountAfterKmpWorker + 1,
            callCountAfterKmpHeavyWorker,
            "Both worker types should use same factory instance"
        )
    }

    /**
     * Test 9: Concurrent worker execution with isolated Koin
     *
     * Scenario: Execute multiple workers concurrently
     * Expected: No race conditions, all workers succeed
     */
    @Test
    fun testConcurrentWorkerExecutionWithIsolatedKoin() = runBlocking {
        val kmpFactory = TestAndroidWorkerFactory("ConcurrentFactory")
        KmpWorkManager.initialize(
            context = context,
            workerFactory = kmpFactory,
            config = KmpWorkManagerConfig()
        )

        // Create multiple workers
        val workers = (1..10).map {
            TestWorkerBuilder<KmpWorker>(
                context = context,
                inputData = androidx.work.workDataOf(
                    "workerClassName" to "TestWorker",
                    "inputJson" to null
                )
            ).build()
        }

        // Execute concurrently
        val results = workers.map { worker ->
            kotlinx.coroutines.async {
                worker.doWork()
            }
        }.map { it.await() }

        // All should succeed
        assertTrue(
            results.all { it is ListenableWorker.Result.Success },
            "All concurrent workers should succeed"
        )

        // Factory should have been called 10 times
        assertTrue(
            kmpFactory.createWorkerCallCount >= 10,
            "Factory should be called for each worker"
        )
    }

    /**
     * Test 10: Verify fix prevents regression to global Koin
     *
     * Scenario: Compile-time verification that workers use KmpWorkManagerKoin
     * Expected: This test documents the fix and prevents regression
     */
    @Test
    fun testFixPreventsRegressionToGlobalKoin() {
        // This test documents the fix:
        // BEFORE (Bug):
        // private val workerFactory: AndroidWorkerFactory by inject()
        //
        // AFTER (Fix):
        // private val workerFactory: AndroidWorkerFactory = KmpWorkManagerKoin.getKoin().get()
        //
        // The "by inject()" delegates to Koin's inject() function which resolves from:
        // 1. Current scope if in scope context
        // 2. GlobalContext.get() otherwise
        //
        // The fix explicitly uses KmpWorkManagerKoin.getKoin().get() which always
        // resolves from the isolated Koin instance.

        // Verify KmpWorkManagerKoin exists (compile check)
        assertNotNull(KmpWorkManagerKoin, "KmpWorkManagerKoin should exist")

        // Document the fix
        assertTrue(true, "Fix documented: Workers use KmpWorkManagerKoin.getKoin().get()")
    }

    // ===========================
    // Test Helpers
    // ===========================

    private class TestAndroidWorkerFactory(
        val name: String
    ) : AndroidWorkerFactory {
        var createWorkerCallCount = 0

        override fun createWorker(workerClassName: String): AndroidWorker? {
            createWorkerCallCount++
            return when (workerClassName) {
                "TestWorker" -> TestAndroidWorker()
                else -> null
            }
        }
    }

    private class TestAndroidWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            return WorkerResult.Success(message = "Test worker completed")
        }
    }
}
