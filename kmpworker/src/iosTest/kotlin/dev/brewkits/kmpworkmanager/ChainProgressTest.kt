package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import kotlin.test.*

/**
 * Unit tests for ChainProgress data model.
 * Tests state tracking, completion logic, retry handling, and progress calculations.
 */
class ChainProgressTest {

    @Test
    fun `new ChainProgress should have empty completed steps`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5
        )

        assertEquals("test-chain", progress.chainId)
        assertEquals(5, progress.totalSteps)
        assertTrue(progress.completedSteps.isEmpty())
        assertNull(progress.lastFailedStep)
        assertEquals(0, progress.retryCount)
        assertEquals(3, progress.maxRetries)
    }

    @Test
    fun `isStepCompleted should return true for completed steps`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 3)
        )

        assertTrue(progress.isStepCompleted(0))
        assertTrue(progress.isStepCompleted(1))
        assertFalse(progress.isStepCompleted(2))
        assertTrue(progress.isStepCompleted(3))
        assertFalse(progress.isStepCompleted(4))
    }

    @Test
    fun `getNextStepIndex should return first incomplete step`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1)
        )

        assertEquals(2, progress.getNextStepIndex())
    }

    @Test
    fun `getNextStepIndex should return null when all steps completed`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 3,
            completedSteps = listOf(0, 1, 2)
        )

        assertNull(progress.getNextStepIndex())
    }

    @Test
    fun `getNextStepIndex should handle non-sequential completed steps`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 2, 4) // Skip 1 and 3
        )

        // Should return the first incomplete step (step 1)
        assertEquals(1, progress.getNextStepIndex())
    }

    @Test
    fun `withCompletedStep should add step to completed list`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1)
        )

        val updated = progress.withCompletedStep(2)

        assertEquals(listOf(0, 1, 2), updated.completedSteps)
        assertNull(updated.lastFailedStep) // Cleared on success
    }

    @Test
    fun `withCompletedStep should keep steps sorted`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 2)
        )

        val updated = progress.withCompletedStep(1)

        assertEquals(listOf(0, 1, 2), updated.completedSteps)
    }

    @Test
    fun `withCompletedStep should not duplicate steps`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2)
        )

        val updated = progress.withCompletedStep(1) // Already completed

        assertEquals(listOf(0, 1, 2), updated.completedSteps)
        assertEquals(progress, updated) // Should return same instance
    }

    @Test
    fun `withCompletedStep should clear lastFailedStep`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0),
            lastFailedStep = 1,
            retryCount = 1
        )

        val updated = progress.withCompletedStep(1)

        assertNull(updated.lastFailedStep)
        assertEquals(1, updated.retryCount) // Retry count preserved
    }

    @Test
    fun `withFailure should increment retry count and set failed step`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 0
        )

        val updated = progress.withFailure(2)

        assertEquals(1, updated.retryCount)
        assertEquals(2, updated.lastFailedStep)
    }

    @Test
    fun `withFailure should accumulate retry count`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 1
        )

        val updated = progress.withFailure(2)

        assertEquals(2, updated.retryCount)
    }

    @Test
    fun `hasExceededRetries should return false when under limit`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 2,
            maxRetries = 3
        )

        assertFalse(progress.hasExceededRetries())
    }

    @Test
    fun `hasExceededRetries should return true when at limit`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 3,
            maxRetries = 3
        )

        assertTrue(progress.hasExceededRetries())
    }

    @Test
    fun `hasExceededRetries should return true when over limit`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 4,
            maxRetries = 3
        )

        assertTrue(progress.hasExceededRetries())
    }

    @Test
    fun `isComplete should return false for partial completion`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2)
        )

        assertFalse(progress.isComplete())
    }

    @Test
    fun `isComplete should return true when all steps completed`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 3,
            completedSteps = listOf(0, 1, 2)
        )

        assertTrue(progress.isComplete())
    }

    @Test
    fun `getCompletionPercentage should calculate correctly`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 4,
            completedSteps = listOf(0, 1) // 2/4 = 50%
        )

        assertEquals(50, progress.getCompletionPercentage())
    }

    @Test
    fun `getCompletionPercentage should return 0 for no completion`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5
        )

        assertEquals(0, progress.getCompletionPercentage())
    }

    @Test
    fun `getCompletionPercentage should return 100 for full completion`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2, 3, 4)
        )

        assertEquals(100, progress.getCompletionPercentage())
    }

    @Test
    fun `getCompletionPercentage should handle zero total steps`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 0
        )

        assertEquals(100, progress.getCompletionPercentage())
    }

    @Test
    fun `retry scenario - fail then succeed should clear failure`() {
        // Initial state
        var progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 3
        )

        // Step 0 succeeds
        progress = progress.withCompletedStep(0)
        assertEquals(listOf(0), progress.completedSteps)
        assertNull(progress.lastFailedStep)

        // Step 1 fails
        progress = progress.withFailure(1)
        assertEquals(1, progress.lastFailedStep)
        assertEquals(1, progress.retryCount)

        // Retry step 1 - succeeds this time
        progress = progress.withCompletedStep(1)
        assertEquals(listOf(0, 1), progress.completedSteps)
        assertNull(progress.lastFailedStep) // Cleared
        assertEquals(1, progress.retryCount) // Preserved for tracking

        // Step 2 succeeds
        progress = progress.withCompletedStep(2)
        assertTrue(progress.isComplete())
    }

    @Test
    fun `custom max retries should be respected`() {
        val progress = ChainProgress(
            chainId = "test-chain",
            totalSteps = 5,
            retryCount = 5,
            maxRetries = 10
        )

        assertFalse(progress.hasExceededRetries())

        val exceeded = progress.copy(retryCount = 10)
        assertTrue(exceeded.hasExceededRetries())
    }

    @Test
    fun `resume from interruption scenario`() {
        // Chain with 5 steps: [0, 1, 2, 3, 4]
        // Steps 0, 1, 2 completed before interruption
        val progress = ChainProgress(
            chainId = "interrupted-chain",
            totalSteps = 5,
            completedSteps = listOf(0, 1, 2)
        )

        // Should resume from step 3
        assertEquals(3, progress.getNextStepIndex())
        assertEquals(60, progress.getCompletionPercentage())
        assertFalse(progress.isComplete())

        // Complete remaining steps
        val step3 = progress.withCompletedStep(3)
        val step4 = step3.withCompletedStep(4)

        assertTrue(step4.isComplete())
        assertEquals(100, step4.getCompletionPercentage())
        assertNull(step4.getNextStepIndex())
    }
}
