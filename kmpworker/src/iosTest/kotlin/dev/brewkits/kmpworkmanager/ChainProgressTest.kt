package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import kotlinx.serialization.json.Json
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

    // ================================================================
    // completedTasksInSteps — per-task tracking within parallel steps
    // ================================================================

    @Test
    fun `new ChainProgress should have empty completedTasksInSteps`() {
        val progress = ChainProgress(chainId = "test", totalSteps = 3)
        assertTrue(progress.completedTasksInSteps.isEmpty())
    }

    @Test
    fun `isTaskInStepCompleted should return false when no tasks recorded`() {
        val progress = ChainProgress(chainId = "test", totalSteps = 3)
        assertFalse(progress.isTaskInStepCompleted(0, 0))
        assertFalse(progress.isTaskInStepCompleted(1, 2))
    }

    @Test
    fun `isTaskInStepCompleted should return true for recorded task`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(1 to listOf(0, 2))
        )
        assertTrue(progress.isTaskInStepCompleted(1, 0))
        assertTrue(progress.isTaskInStepCompleted(1, 2))
    }

    @Test
    fun `isTaskInStepCompleted should return false for unrecorded task in known step`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(1 to listOf(0, 2))
        )
        assertFalse(progress.isTaskInStepCompleted(1, 1))
    }

    @Test
    fun `isTaskInStepCompleted should return false for unknown step`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(1 to listOf(0))
        )
        assertFalse(progress.isTaskInStepCompleted(99, 0))
    }

    @Test
    fun `withCompletedTaskInStep should add task to empty step`() {
        val progress = ChainProgress(chainId = "test", totalSteps = 3)
        val updated = progress.withCompletedTaskInStep(1, 2)

        assertEquals(mapOf(1 to listOf(2)), updated.completedTasksInSteps)
        assertTrue(updated.isTaskInStepCompleted(1, 2))
    }

    @Test
    fun `withCompletedTaskInStep should append to existing step`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(1 to listOf(0))
        )
        val updated = progress.withCompletedTaskInStep(1, 2)

        assertEquals(mapOf(1 to listOf(0, 2)), updated.completedTasksInSteps)
    }

    @Test
    fun `withCompletedTaskInStep should keep indices sorted`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(0 to listOf(0, 3))
        )
        val updated = progress.withCompletedTaskInStep(0, 1)

        assertEquals(listOf(0, 1, 3), updated.completedTasksInSteps[0])
    }

    @Test
    fun `withCompletedTaskInStep should be idempotent`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(1 to listOf(0, 2))
        )
        val updated = progress.withCompletedTaskInStep(1, 0)

        assertEquals(progress, updated)
    }

    @Test
    fun `withCompletedTaskInStep should track multiple steps independently`() {
        var progress = ChainProgress(chainId = "test", totalSteps = 3)
        progress = progress.withCompletedTaskInStep(0, 0)
        progress = progress.withCompletedTaskInStep(0, 1)
        progress = progress.withCompletedTaskInStep(2, 3)

        assertEquals(listOf(0, 1), progress.completedTasksInSteps[0])
        assertNull(progress.completedTasksInSteps[1])
        assertEquals(listOf(3), progress.completedTasksInSteps[2])
    }

    @Test
    fun `withCompletedStep should clear per-task data for that step`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(
                0 to listOf(0, 1, 2),
                1 to listOf(0, 1)
            )
        )
        val updated = progress.withCompletedStep(0)

        assertFalse(updated.completedTasksInSteps.containsKey(0))
        assertEquals(listOf(0, 1), updated.completedTasksInSteps[1])
        assertTrue(updated.isStepCompleted(0))
    }

    @Test
    fun `withCompletedStep should not affect other steps per-task data`() {
        val progress = ChainProgress(
            chainId = "test",
            totalSteps = 3,
            completedTasksInSteps = mapOf(
                0 to listOf(0, 1),
                1 to listOf(0, 1, 2),
                2 to listOf(0)
            )
        )
        val updated = progress.withCompletedStep(1)

        assertEquals(listOf(0, 1), updated.completedTasksInSteps[0])
        assertFalse(updated.completedTasksInSteps.containsKey(1))
        assertEquals(listOf(0), updated.completedTasksInSteps[2])
    }

    // ================================================================
    // Full parallel retry scenario
    // ================================================================

    @Test
    fun `parallel retry scenario - only failed tasks should be re-executed`() {
        var progress = ChainProgress(chainId = "parallel-chain", totalSteps = 3)

        // Step 0 completes (single task)
        progress = progress.withCompletedStep(0)

        // Step 1 is parallel with 4 tasks.  Tasks 0 and 2 succeed; 1 and 3 fail.
        progress = progress.withCompletedTaskInStep(1, 0)
        progress = progress.withCompletedTaskInStep(1, 2)
        progress = progress.withFailure(1)

        // --- state before retry ---
        assertTrue(progress.isTaskInStepCompleted(1, 0))   // succeeded
        assertFalse(progress.isTaskInStepCompleted(1, 1))  // failed — must re-run
        assertTrue(progress.isTaskInStepCompleted(1, 2))   // succeeded
        assertFalse(progress.isTaskInStepCompleted(1, 3))  // failed — must re-run
        assertTrue(progress.isStepCompleted(0))
        assertFalse(progress.isStepCompleted(1))
        assertEquals(1, progress.retryCount)

        // --- retry: only tasks 1 and 3 execute, both succeed ---
        progress = progress.withCompletedTaskInStep(1, 1)
        progress = progress.withCompletedTaskInStep(1, 3)
        progress = progress.withCompletedStep(1)

        assertTrue(progress.isStepCompleted(1))
        assertFalse(progress.completedTasksInSteps.containsKey(1)) // cleaned up
        assertEquals(listOf(0, 1), progress.completedSteps)
    }

    // ================================================================
    // Serialization round-trip and backward compatibility
    // ================================================================

    @Test
    fun `serialization round-trip preserves completedTasksInSteps`() {
        val original = ChainProgress(
            chainId = "serial-test",
            totalSteps = 3,
            completedSteps = listOf(0),
            completedTasksInSteps = mapOf(1 to listOf(0, 2)),
            lastFailedStep = 1,
            retryCount = 2,
            maxRetries = 5
        )

        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<ChainProgress>(json)

        assertEquals(original, decoded)
        assertTrue(decoded.isTaskInStepCompleted(1, 0))
        assertTrue(decoded.isTaskInStepCompleted(1, 2))
        assertFalse(decoded.isTaskInStepCompleted(1, 1))
    }

    @Test
    fun `deserialization of legacy JSON without completedTasksInSteps defaults to empty map`() {
        val legacyJson = """{"chainId":"legacy","totalSteps":3,"completedSteps":[0,1],"lastFailedStep":null,"retryCount":1,"maxRetries":3}"""
        val decoded = Json.decodeFromString<ChainProgress>(legacyJson)

        assertEquals("legacy", decoded.chainId)
        assertEquals(listOf(0, 1), decoded.completedSteps)
        assertTrue(decoded.completedTasksInSteps.isEmpty())
        assertEquals(1, decoded.retryCount)
    }
}
