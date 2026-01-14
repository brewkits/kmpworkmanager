package io.brewkits.kmpworkmanager

import io.brewkits.kmpworkmanager.background.data.ChainProgress
import io.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlin.test.*

/**
 * Unit tests for ChainExecutor logic.
 *
 * These tests focus on:
 * - Chain execution flow and progress tracking
 * - Retry logic and max retries handling
 * - State restoration after interruptions
 * - Timeout handling
 * - Step completion and failure scenarios
 *
 * Note: Due to the complexity of mocking iOS-specific dependencies (IosWorkerFactory, IosFileStorage)
 * and native objects (NSMutableSet), these tests focus on the ChainProgress model behavior
 * which is the core state management logic used by ChainExecutor.
 *
 * For integration testing of the full ChainExecutor, run tests on iOS simulator/device.
 */
class ChainExecutorTest {

    // ==================== Progress Tracking Scenarios ====================

    @Test
    fun `chain execution should track progress step by step`() {
        // Simulate 5-step chain execution
        var progress = ChainProgress(chainId = "test-chain", totalSteps = 5)

        // Initial state
        assertEquals(0, progress.getNextStepIndex())
        assertFalse(progress.isComplete())

        // Step 0 completes
        progress = progress.withCompletedStep(0)
        assertEquals(1, progress.getNextStepIndex())
        assertEquals(20, progress.getCompletionPercentage())

        // Step 1 completes
        progress = progress.withCompletedStep(1)
        assertEquals(2, progress.getNextStepIndex())
        assertEquals(40, progress.getCompletionPercentage())

        // Step 2 completes
        progress = progress.withCompletedStep(2)
        assertEquals(3, progress.getNextStepIndex())
        assertEquals(60, progress.getCompletionPercentage())

        // Step 3 completes
        progress = progress.withCompletedStep(3)
        assertEquals(4, progress.getNextStepIndex())
        assertEquals(80, progress.getCompletionPercentage())

        // Step 4 completes
        progress = progress.withCompletedStep(4)
        assertNull(progress.getNextStepIndex())
        assertTrue(progress.isComplete())
        assertEquals(100, progress.getCompletionPercentage())
    }

    @Test
    fun `chain should resume from last completed step after interruption`() {
        // Chain interrupted after step 2
        val progress = ChainProgress(
            chainId = "interrupted-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2) // Steps 0-2 completed before interruption
        )

        // Should resume from step 3
        assertEquals(3, progress.getNextStepIndex())
        assertEquals(listOf(0, 1, 2), progress.completedSteps)
        assertEquals(60, progress.getCompletionPercentage())

        // Verify completed steps are skipped
        assertTrue(progress.isStepCompleted(0))
        assertTrue(progress.isStepCompleted(1))
        assertTrue(progress.isStepCompleted(2))
        assertFalse(progress.isStepCompleted(3))
        assertFalse(progress.isStepCompleted(4))
    }

    // ==================== Retry Logic ====================

    @Test
    fun `chain should track retry count on failures`() {
        var progress = ChainProgress(
            chainId = "retry-chain",
            totalSteps = 3,
            maxRetries = 3
        )

        // Step 0 succeeds
        progress = progress.withCompletedStep(0)
        assertEquals(0, progress.retryCount)

        // Step 1 fails
        progress = progress.withFailure(1)
        assertEquals(1, progress.retryCount)
        assertEquals(1, progress.lastFailedStep)
        assertFalse(progress.hasExceededRetries())

        // Step 1 fails again
        progress = progress.withFailure(1)
        assertEquals(2, progress.retryCount)
        assertFalse(progress.hasExceededRetries())

        // Step 1 fails third time
        progress = progress.withFailure(1)
        assertEquals(3, progress.retryCount)
        assertTrue(progress.hasExceededRetries()) // Should abandon
    }

    @Test
    fun `chain should be abandoned after max retries exceeded`() {
        val progress = ChainProgress(
            chainId = "abandon-chain",
            totalSteps = 3,
            retryCount = 3,
            maxRetries = 3
        )

        assertTrue(progress.hasExceededRetries())
        // Chain should be deleted by ChainExecutor
    }

    @Test
    fun `successful step should clear lastFailedStep`() {
        // Step fails initially
        var progress = ChainProgress(
            chainId = "recover-chain",
            totalSteps = 3
        )

        progress = progress.withFailure(1)
        assertEquals(1, progress.lastFailedStep)
        assertEquals(1, progress.retryCount)

        // Step succeeds on retry
        progress = progress.withCompletedStep(1)
        assertNull(progress.lastFailedStep) // Cleared
        assertEquals(1, progress.retryCount) // Preserved for tracking
        assertTrue(progress.isStepCompleted(1))
    }

    @Test
    fun `custom max retries should be respected`() {
        var progress = ChainProgress(
            chainId = "custom-retry-chain",
            totalSteps = 2,
            maxRetries = 5 // Custom max
        )

        // Fail 4 times - should not exceed
        repeat(4) {
            progress = progress.withFailure(0)
        }
        assertEquals(4, progress.retryCount)
        assertFalse(progress.hasExceededRetries())

        // 5th failure - should exceed
        progress = progress.withFailure(0)
        assertEquals(5, progress.retryCount)
        assertTrue(progress.hasExceededRetries())
    }

    // ==================== Complex Execution Scenarios ====================

    @Test
    fun `chain with mixed success and failure should handle correctly`() {
        var progress = ChainProgress(
            chainId = "mixed-chain",
            totalSteps = 5
        )

        // Step 0: Success
        progress = progress.withCompletedStep(0)
        assertEquals(listOf(0), progress.completedSteps)

        // Step 1: Fail
        progress = progress.withFailure(1)
        assertEquals(1, progress.retryCount)
        assertEquals(1, progress.lastFailedStep)

        // Step 1: Retry success
        progress = progress.withCompletedStep(1)
        assertEquals(listOf(0, 1), progress.completedSteps)
        assertNull(progress.lastFailedStep)

        // Step 2: Success
        progress = progress.withCompletedStep(2)

        // Step 3: Fail
        progress = progress.withFailure(3)
        assertEquals(2, progress.retryCount)

        // Step 3: Retry success
        progress = progress.withCompletedStep(3)

        // Step 4: Success
        progress = progress.withCompletedStep(4)

        // Verify final state
        assertTrue(progress.isComplete())
        assertEquals(2, progress.retryCount) // Total retries across all steps
        assertNull(progress.lastFailedStep)
    }

    @Test
    fun `interrupted chain should preserve retry count on resume`() {
        // Chain interrupted after 1 failure and 2 successful steps
        val progress = ChainProgress(
            chainId = "preserve-retry-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1),
            lastFailedStep = 2,
            retryCount = 1 // Had one failure before interruption
        )

        // On resume, retry count should be preserved
        assertEquals(1, progress.retryCount)
        assertEquals(2, progress.lastFailedStep)
        assertEquals(2, progress.getNextStepIndex())

        // If step 2 fails again on resume
        val afterFailure = progress.withFailure(2)
        assertEquals(2, afterFailure.retryCount)
    }

    @Test
    fun `multiple interruptions should accumulate state correctly`() {
        // First execution: Steps 0-1 complete, then timeout
        var progress = ChainProgress(
            chainId = "multi-interrupt-chain",
            totalSteps = 10,
            completedSteps = listOf(0, 1)
        )
        assertEquals(2, progress.getNextStepIndex())

        // Second execution: Steps 2-4 complete, then timeout
        progress = progress.copy(completedSteps = listOf(0, 1, 2, 3, 4))
        assertEquals(5, progress.getNextStepIndex())
        assertEquals(50, progress.getCompletionPercentage())

        // Third execution: Steps 5-9 complete
        progress = progress.copy(completedSteps = (0..9).toList())
        assertTrue(progress.isComplete())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `chain with single step should work correctly`() {
        var progress = ChainProgress(
            chainId = "single-step-chain",
            totalSteps = 1
        )

        assertEquals(0, progress.getNextStepIndex())
        assertEquals(0, progress.getCompletionPercentage())

        progress = progress.withCompletedStep(0)
        assertTrue(progress.isComplete())
        assertEquals(100, progress.getCompletionPercentage())
        assertNull(progress.getNextStepIndex())
    }

    @Test
    fun `chain with zero steps should report as complete`() {
        val progress = ChainProgress(
            chainId = "empty-chain",
            totalSteps = 0
        )

        assertTrue(progress.isComplete())
        assertEquals(100, progress.getCompletionPercentage())
        assertNull(progress.getNextStepIndex())
    }

    @Test
    fun `non-sequential step completion should still work`() {
        var progress = ChainProgress(
            chainId = "non-sequential-chain",
            totalSteps = 5
        )

        // Complete steps out of order (e.g., due to parallel execution or resume)
        progress = progress.withCompletedStep(2)
        progress = progress.withCompletedStep(0)
        progress = progress.withCompletedStep(4)

        // Completed steps should be sorted
        assertEquals(listOf(0, 2, 4), progress.completedSteps)

        // Next step should be first incomplete (step 1)
        assertEquals(1, progress.getNextStepIndex())
    }

    @Test
    fun `completing same step multiple times should be idempotent`() {
        var progress = ChainProgress(
            chainId = "idempotent-chain",
            totalSteps = 3
        )

        progress = progress.withCompletedStep(0)
        val afterFirst = progress.completedSteps

        // Complete same step again
        progress = progress.withCompletedStep(0)
        assertEquals(afterFirst, progress.completedSteps)

        // Should still have only one entry for step 0
        assertEquals(listOf(0), progress.completedSteps)
    }

    // ==================== Timeout Scenarios ====================

    @Test
    fun `chain timeout should preserve progress for resume`() {
        // Chain times out after completing 3 out of 10 steps
        val progress = ChainProgress(
            chainId = "timeout-chain",
            totalSteps = 10,
            completedSteps = listOf(0, 1, 2)
        )

        // Progress should be preserved for next execution
        assertEquals(3, progress.getNextStepIndex())
        assertEquals(30, progress.getCompletionPercentage())
        assertEquals(0, progress.retryCount) // No failures, just timeout

        // On resume, should continue from step 3
        assertFalse(progress.isStepCompleted(3))
        assertTrue(progress.isStepCompleted(0))
        assertTrue(progress.isStepCompleted(1))
        assertTrue(progress.isStepCompleted(2))
    }

    @Test
    fun `chain timing out at last step should only need one more execution`() {
        val progress = ChainProgress(
            chainId = "almost-done-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2, 3) // Only step 4 remaining
        )

        assertEquals(4, progress.getNextStepIndex())
        assertEquals(80, progress.getCompletionPercentage())
        assertFalse(progress.isComplete())

        // Complete last step
        val completed = progress.withCompletedStep(4)
        assertTrue(completed.isComplete())
    }

    // ==================== Batch Processing Simulation ====================

    @Test
    fun `simulated batch processing of multiple chains`() {
        // Simulate multiple chains at different stages
        val chains = listOf(
            ChainProgress(chainId = "chain-1", totalSteps = 3, completedSteps = emptyList()),
            ChainProgress(chainId = "chain-2", totalSteps = 5, completedSteps = listOf(0, 1)),
            ChainProgress(chainId = "chain-3", totalSteps = 2, completedSteps = listOf(0, 1))
        )

        // Chain 1: Not started
        assertEquals(0, chains[0].getNextStepIndex())
        assertEquals(0, chains[0].getCompletionPercentage())

        // Chain 2: Partially complete
        assertEquals(2, chains[1].getNextStepIndex())
        assertEquals(40, chains[1].getCompletionPercentage())

        // Chain 3: Complete
        assertTrue(chains[2].isComplete())
        assertNull(chains[2].getNextStepIndex())
    }

    // ==================== Progress Calculation Accuracy ====================

    @Test
    fun `progress percentage should be accurate for various scenarios`() {
        val scenarios = listOf(
            Triple(1, listOf(0), 100),        // 1/1 = 100%
            Triple(2, listOf(0), 50),         // 1/2 = 50%
            Triple(3, listOf(0, 1), 66),      // 2/3 = 66%
            Triple(4, listOf(0, 1, 2), 75),   // 3/4 = 75%
            Triple(5, listOf(0, 1), 40),      // 2/5 = 40%
            Triple(10, listOf(0, 1, 2), 30)   // 3/10 = 30%
        )

        scenarios.forEach { (totalSteps, completed, expected) ->
            val progress = ChainProgress(
                chainId = "calc-test",
                totalSteps = totalSteps,
                completedSteps = completed
            )
            assertEquals(
                expected,
                progress.getCompletionPercentage(),
                "Failed for $completed/$totalSteps"
            )
        }
    }
}
