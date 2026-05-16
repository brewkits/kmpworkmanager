package dev.brewkits.kmpworkmanager.background.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the v2.5 per-step attempt counter on [ChainProgress].
 *
 * These pin down the semantics that the iOS [ChainExecutor] depends on when honouring
 * `WorkerResult.Retry.attemptCap`: each step has its own counter, and the counter is
 * incremented on every observed Retry/Failure for that step regardless of the
 * chain-level [ChainProgress.retryCount].
 *
 * The actual end-to-end retry flow on iOS lives in `ChainExecutorTest`/QA suites; this
 * file only verifies the data model contract.
 */
class ChainProgressRetryTest {

    private fun fresh(totalSteps: Int = 3) =
        ChainProgress(chainId = "c1", totalSteps = totalSteps)

    @Test
    fun stepAttempts_isZero_forFreshChain() {
        val p = fresh()
        assertEquals(0, p.stepAttempts(0))
        assertEquals(0, p.stepAttempts(1))
        assertEquals(0, p.stepAttempts(2))
    }

    @Test
    fun withStepAttempt_increments_onlyTargetStep() {
        val p = fresh()
            .withStepAttempt(1)
            .withStepAttempt(1)
            .withStepAttempt(2)

        assertEquals(0, p.stepAttempts(0))
        assertEquals(2, p.stepAttempts(1), "step 1 attempted twice")
        assertEquals(1, p.stepAttempts(2), "step 2 attempted once")
    }

    @Test
    fun withStepAttempt_isIndependentOf_retryCount() {
        // Chain-level retry counter and per-step attempt counter are orthogonal —
        // bumping one does not change the other. The iOS executor relies on this.
        val p = fresh()
            .withFailure(0, "boom")
            .withFailure(0, "boom again")
        // Chain-level retryCount bumped twice; per-step counter untouched.
        assertEquals(2, p.retryCount)
        assertEquals(0, p.stepAttempts(0))

        val p2 = p.withStepAttempt(0)
        assertEquals(2, p2.retryCount, "step attempt bump must NOT touch chain retryCount")
        assertEquals(1, p2.stepAttempts(0))
    }

    @Test
    fun stepRetryCounts_serializable_acrossCopy() {
        // ChainProgress is @Serializable. Ensure the new field round-trips correctly
        // through a `copy()` — which is the path used by all the with* helpers.
        val p = fresh().withStepAttempt(0).withStepAttempt(0).withStepAttempt(1)
        val copy = p.copy(retryCount = 5)
        assertEquals(2, copy.stepAttempts(0))
        assertEquals(1, copy.stepAttempts(1))
        assertEquals(5, copy.retryCount)
    }

    @Test
    fun hasExceededRetries_unchanged_byStepAttempts() {
        // Per-step counter must not accidentally count towards the chain-level cap.
        val maxRetries = 3
        var p = fresh().copy(maxRetries = maxRetries)
        repeat(maxRetries + 2) { p = p.withStepAttempt(0) }
        assertFalse(p.hasExceededRetries(), "step attempts must not trigger chain-level retry cap")
    }

    @Test
    fun completedStep_clearsPerTaskMap_butKeepsStepAttempts() {
        // When a step finally completes, withCompletedStep clears its per-task map but
        // intentionally KEEPS the attempt counter — it remains visible to telemetry /
        // post-mortem analysis for that chain.
        val p = fresh()
            .withCompletedTaskInStep(0, 0)
            .withStepAttempt(0)
            .withStepAttempt(0)
            .withCompletedStep(0)
        assertTrue(p.isStepCompleted(0))
        assertEquals(emptyList<Int>(), p.completedTasksInSteps[0] ?: emptyList())
        assertEquals(2, p.stepAttempts(0), "step attempts should remain visible after completion")
    }
}
