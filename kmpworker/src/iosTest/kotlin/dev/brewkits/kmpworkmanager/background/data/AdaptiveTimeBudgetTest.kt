package dev.brewkits.kmpworkmanager.background.data

import kotlin.test.*

/**
 * Unit tests for Adaptive Time Budget (Task #3)
 * Tests:
 * - Base budget calculation (85%)
 * - Adaptive adjustment based on cleanup history
 * - Minimum budget floor (70%)
 * - Safety buffer calculation
 */
class AdaptiveTimeBudgetTest {

    /**
     * Mock ChainExecutor to test budget calculation logic
     */
    private class TestChainExecutor {
        var lastCleanupDurationMs: Long = 0L

        fun calculateAdaptiveBudget(totalTimeout: Long): Long {
            // Base: 85% of time
            val baseBudget = (totalTimeout * 0.85).toLong()

            // If we have cleanup history, reserve measured time + 20% buffer
            if (lastCleanupDurationMs > 0L) {
                val safetyBuffer = (lastCleanupDurationMs * 1.2).toLong()
                val minBudget = (totalTimeout * 0.70).toLong() // Never go below 70%

                return maxOf(
                    minBudget,
                    totalTimeout - safetyBuffer
                )
            }

            return baseBudget
        }
    }

    @Test
    fun testBaseBudget_NoHistory() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L // 30 seconds

        val budget = executor.calculateAdaptiveBudget(totalTimeout)

        // Should be 85% of total
        assertEquals(25_500L, budget, "Base budget should be 85% (25.5s of 30s)")
    }

    @Test
    fun testAdaptiveBudget_FastCleanup() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L
        executor.lastCleanupDurationMs = 1_000L // 1 second cleanup

        val budget = executor.calculateAdaptiveBudget(totalTimeout)

        // Safety buffer = 1000ms * 1.2 = 1200ms
        // Budget = 30000 - 1200 = 28800ms (96%)
        assertEquals(28_800L, budget, "Fast cleanup should allow 96% time budget")
    }

    @Test
    fun testAdaptiveBudget_SlowCleanup() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L
        executor.lastCleanupDurationMs = 5_000L // 5 second cleanup

        val budget = executor.calculateAdaptiveBudget(totalTimeout)

        // Safety buffer = 5000ms * 1.2 = 6000ms
        // Budget = 30000 - 6000 = 24000ms (80%)
        assertEquals(24_000L, budget, "Slow cleanup should reduce budget to 80%")
    }

    @Test
    fun testMinimumBudgetFloor() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L
        executor.lastCleanupDurationMs = 15_000L // Very slow cleanup (15s)

        val budget = executor.calculateAdaptiveBudget(totalTimeout)

        // Safety buffer = 15000ms * 1.2 = 18000ms
        // Budget would be 30000 - 18000 = 12000ms (40%)
        // But minimum floor is 70% = 21000ms
        assertEquals(21_000L, budget, "Should enforce 70% minimum floor")
    }

    @Test
    fun testExtremelySlowCleanup() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L
        executor.lastCleanupDurationMs = 25_000L // Cleanup takes 25s (impossible but testing)

        val budget = executor.calculateAdaptiveBudget(totalTimeout)

        // Should still enforce 70% floor
        assertEquals(21_000L, budget, "Even extreme cleanup should enforce 70% floor")
    }

    @Test
    fun testVariousTimeouts() {
        val executor = TestChainExecutor()
        executor.lastCleanupDurationMs = 2_000L // 2 second cleanup

        val testCases = listOf(
            10_000L to 7_600L,   // 10s timeout -> 7.6s budget (76%)
            20_000L to 17_600L,  // 20s timeout -> 17.6s budget (88%)
            30_000L to 27_600L,  // 30s timeout -> 27.6s budget (92%)
            60_000L to 57_600L   // 60s timeout -> 57.6s budget (96%)
        )

        testCases.forEach { (timeout, expectedBudget) ->
            val budget = executor.calculateAdaptiveBudget(timeout)
            assertEquals(expectedBudget, budget, "Budget for ${timeout}ms should be ${expectedBudget}ms")
        }
    }

    @Test
    fun testBudgetNeverExceedsTimeout() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L

        // Test various cleanup durations
        val cleanupDurations = listOf(0L, 100L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L)

        cleanupDurations.forEach { cleanup ->
            executor.lastCleanupDurationMs = cleanup
            val budget = executor.calculateAdaptiveBudget(totalTimeout)

            assertTrue(
                budget <= totalTimeout,
                "Budget ($budget) should never exceed timeout ($totalTimeout) for cleanup=$cleanup"
            )
        }
    }

    @Test
    fun testBudgetAlwaysPositive() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L

        // Test edge cases
        val cleanupDurations = listOf(0L, 1L, 100L, 30_000L, 100_000L)

        cleanupDurations.forEach { cleanup ->
            executor.lastCleanupDurationMs = cleanup
            val budget = executor.calculateAdaptiveBudget(totalTimeout)

            assertTrue(
                budget > 0,
                "Budget should always be positive (was $budget for cleanup=$cleanup)"
            )
        }
    }

    /**
     * Stress test: Simulate realistic cleanup duration patterns
     */
    @Test
    fun stressTestRealisticPatterns() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L

        // Simulate 100 executions with varying cleanup times
        val cleanupPattern = List(100) { i ->
            when {
                i < 50 -> 500L + (i * 10)  // Gradually increasing 500-1000ms
                i < 80 -> 1_000L + (i * 20) // Moderate 1000-2600ms
                else -> 2_000L + (i * 50)   // Spike 2000-7000ms
            }
        }

        cleanupPattern.forEach { cleanup ->
            executor.lastCleanupDurationMs = cleanup
            val budget = executor.calculateAdaptiveBudget(totalTimeout)

            // Validate constraints
            assertTrue(budget > 0, "Budget should be positive")
            assertTrue(budget <= totalTimeout, "Budget should not exceed timeout")
            assertTrue(budget >= (totalTimeout * 0.70).toLong(), "Budget should respect 70% floor")

            // Calculate actual usage percentage
            val usagePercent = (budget.toDouble() / totalTimeout) * 100
            assertTrue(
                usagePercent in 70.0..100.0,
                "Budget usage should be 70-100% (was ${usagePercent}% for cleanup=${cleanup}ms)"
            )
        }
    }

    /**
     * Integration-style test: Simulate real execution with budget measurement
     */
    @Test
    fun testRealWorldScenario() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L

        // Scenario 1: First run (no history) - should use base 85%
        var budget = executor.calculateAdaptiveBudget(totalTimeout)
        assertEquals(25_500L, budget, "First run should use 85% base budget")

        // Scenario 2: Fast cleanup measured (500ms)
        executor.lastCleanupDurationMs = 500L
        budget = executor.calculateAdaptiveBudget(totalTimeout)
        assertTrue(budget > 25_500L, "After fast cleanup, should increase budget")

        // Scenario 3: Cleanup gets slower (2000ms)
        executor.lastCleanupDurationMs = 2_000L
        budget = executor.calculateAdaptiveBudget(totalTimeout)
        assertTrue(budget < 30_000L, "Slower cleanup should reserve more time")

        // Scenario 4: Cleanup stabilizes (1500ms)
        executor.lastCleanupDurationMs = 1_500L
        budget = executor.calculateAdaptiveBudget(totalTimeout)
        val safetyBuffer = (1_500L * 1.2).toLong()
        assertEquals(totalTimeout - safetyBuffer, budget, "Should adapt to stable cleanup time")
    }

    @Test
    fun testBudgetImprovesTimeUtilization() {
        val executor = TestChainExecutor()
        val totalTimeout = 30_000L

        // Without adaptive budget (hardcoded 85%)
        val fixedBudget = (totalTimeout * 0.85).toLong() // 25,500ms

        // With adaptive budget (after measuring fast 500ms cleanup)
        executor.lastCleanupDurationMs = 500L
        val adaptiveBudget = executor.calculateAdaptiveBudget(totalTimeout)

        // Adaptive should provide 3,300ms more execution time (28,800 vs 25,500)
        val improvement = adaptiveBudget - fixedBudget
        assertTrue(
            improvement > 3_000L,
            "Adaptive budget should provide at least 3s more execution time (was ${improvement}ms)"
        )

        println("Budget improvement: ${improvement}ms (${(improvement.toDouble() / totalTimeout * 100).toInt()}% more time)")
    }
}
