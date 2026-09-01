package dev.brewkits.kmpworkmanager

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins the structural guarantee the 3.3.0 `ChainExecutor` timeout refactor relies on
 * (ROADMAP.md "Replace `elapsedMs < timeout` heuristic with `withTimeoutOrNull`").
 *
 * `executeChain` and `executeStep` used to disambiguate an inner timeout from an outer
 * one by comparing elapsed wall-clock time against their own budget
 * (`elapsedMs < chainTimeout` ⇒ "must be outer"). [V250NestedTimeoutMisattributionTest]
 * proves that heuristic's *outcome* was correct for a fast-firing outer timeout, but the
 * heuristic itself has a real gap: CPU starvation or iOS process throttling between the
 * outer cancellation being delivered and the `elapsedNow()` read could push the measured
 * elapsed time past `chainTimeout` even though the outer scope was the actual canceller
 * — misattributing it as a genuine chain timeout. That race can't be reproduced cheaply
 * in a test (it needs a real multi-second scheduling stall), so this file instead proves
 * the *mechanism* that makes the whole class of race impossible: `withTimeoutOrNull`
 * converts a `TimeoutCancellationException` to `null` only when kotlinx.coroutines'
 * internal identity check confirms it is that specific call's own timer — never by
 * comparing elapsed time to a budget. There is no clock read on this path at all, so
 * there is no window for a scheduling delay to corrupt the answer.
 */
class V330TimeoutIdentityDisambiguationTest {

    @Test
    fun outerTimeoutFiring_propagatesThrough_neverAbsorbedAsInnerTimeout() = runTest {
        // The OUTER withTimeout is far shorter than the INNER withTimeoutOrNull's budget,
        // so the outer is unambiguously the actual canceller — the inner call's own timer
        // never gets anywhere close to firing. This is the exact shape of
        // ChainExecutor.executeChain's `withTimeoutOrNull(chainTimeout)` sitting inside
        // executeChainsInBatch's `withTimeout(conservativeTimeout)`.
        assertFailsWith<TimeoutCancellationException>(
            "withTimeoutOrNull must let an outer scope's TimeoutCancellationException " +
                "propagate through unabsorbed — swallowing it as null here is exactly the " +
                "misattribution bug the old elapsed-time heuristic was vulnerable to."
        ) {
            withTimeout(50) {
                withTimeoutOrNull(10_000) {
                    delay(5_000)
                    "unreached"
                }
            }
        }
    }

    @Test
    fun innerTimeoutFiring_withNoOuterScope_resolvesToNullNotException() = runTest {
        // Sanity complement: absent any outer scope, withTimeoutOrNull's own timeout
        // resolves to `null` rather than throwing — this is what `chainOutcome == null`
        // / `taskOutcome == null` now branch on in ChainExecutor.
        val result = withTimeoutOrNull(50) {
            delay(5_000)
            "unreached"
        }
        assertNull(result, "withTimeoutOrNull's own timeout must resolve to null, not throw")
    }

    @Test
    fun innerTimeoutFiring_insideALongerOuterScope_stillResolvesToNull() = runTest {
        // The inverse nesting: the INNER budget is the one that actually runs out, well
        // before the outer's. This must resolve to `null` inside the outer scope, exactly
        // like a genuine ChainExecutor chain/task timeout firing while there is no
        // executeChainsInBatch-level timeout anywhere near expiring.
        val result = withTimeout(10_000) {
            withTimeoutOrNull(50) {
                delay(5_000)
                "unreached"
            }
        }
        assertNull(result, "an inner timeout nested inside a longer outer scope must still resolve to null")
    }
}
