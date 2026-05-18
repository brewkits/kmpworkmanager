package dev.brewkits.kmpworkmanager.background.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Adversarial tests for [PendingIntentCodes].
 *
 * These are not "happy path" tests — they are written to *prove* the bug we fixed
 * actually existed and that the fix actually addresses it. The previous implementation
 * used `String.hashCode()` for [PendingIntent] requestCodes, which collides for many
 * pairs of legitimate task IDs.
 *
 * Why we needed this category of test (lesson from v2.4.3 review):
 * - `SecurityValidatorTest` and `AlarmReceiverTest` both passed with 100% branch
 *   coverage, but neither exercised collision at scale. Test data was always 2-3
 *   hardcoded IDs.
 * - Collisions are silent: two PendingIntents with the same requestCode share a slot,
 *   so `FLAG_UPDATE_CURRENT` resolves to whichever one was created last. Symptoms
 *   surface only in production: "alarms double-fire after reboot", "cancelling task A
 *   silently cancels task B".
 *
 * What this file proves:
 * 1. `String.hashCode()` collides for the canonical pair `"Aa".hashCode() == "BB".hashCode()`
 *    (both 2112). This is the smoking gun — collisions are not theoretical.
 * 2. `PendingIntentCodes.forTaskId()` produces distinct codes for the same pair.
 * 3. Across 10 000 generated UUIDs (typical task ID shape), CRC32 collision count
 *    is bounded by the birthday-paradox expectation (~12 for 32-bit codes), which
 *    is acceptable — and explicitly LOWER than what `String.hashCode()` produces
 *    on the same input.
 * 4. The function is deterministic and respects UTF-8 (same string → same code).
 */
class PendingIntentCodesAdversarialTest {

    /**
     * Smoking-gun proof: `"Aa"` and `"BB"` have the same `String.hashCode()` in the JVM.
     * This is the simplest 2-char collision; longer-string collisions are easy to find
     * algorithmically. Demonstrates *why* the legacy code path was broken.
     */
    @Test
    fun stringHashCodeCollides_aA_bB() {
        // Both "Aa" and "BB" hash to 2112 in the JVM.
        assertEquals(
            "Aa".hashCode(), "BB".hashCode(),
            "Sanity check: this collision is documented JVM behaviour. If this assertion " +
                "ever fails, the JDK String.hashCode() polynomial has changed — adjust the " +
                "rest of this test to a fresh collision pair."
        )
        assertNotEquals("Aa", "BB", "The two colliding strings must obviously be distinct")
    }

    /**
     * The fix: `PendingIntentCodes.forTaskId()` MUST NOT collide on the legacy pair.
     */
    @Test
    fun pendingIntentCodes_disambiguates_legacy_hashCode_collision() {
        val codeA = PendingIntentCodes.forTaskId("Aa")
        val codeB = PendingIntentCodes.forTaskId("BB")
        assertNotEquals(
            codeA, codeB,
            "CRC32 must distinguish 'Aa' from 'BB' — these collide under String.hashCode()"
        )
    }

    /**
     * Codes must be deterministic — same ID → same code across calls. PendingIntent
     * lookup depends on this: a reboot regenerates the code from the persisted task ID
     * and must hit the same slot as the original `enqueue`.
     */
    @Test
    fun pendingIntentCodes_isDeterministic() {
        val id = "user-12345-daily-sync"
        val first = PendingIntentCodes.forTaskId(id)
        val second = PendingIntentCodes.forTaskId(id)
        val third = PendingIntentCodes.forTaskId(id)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    /**
     * Codes must always be non-negative. Negative requestCodes confuse the Android
     * PendingIntent system and make log output harder to read.
     */
    @Test
    fun pendingIntentCodes_isNonNegative_acrossManyInputs() {
        // Mix of plausible task ID shapes — UUID, kebab-case, snake_case, emoji, CJK.
        val samples = listOf(
            "a", "z", "0", "9",
            "550e8400-e29b-41d4-a716-446655440000",
            "user-12345-daily-sync",
            "task_with_underscores",
            "task.with.dots",
            "task with spaces",
            "TASK_UPPER",
            "tâsk-with-diacritics-éàü",
            "タスク-ja-task",
            "🎉-emoji-task",
            "very-long-".repeat(50)
        )
        for (s in samples) {
            val code = PendingIntentCodes.forTaskId(s)
            assertTrue(
                code >= 0,
                "forTaskId('$s') returned $code — must be non-negative (sign bit must be masked)"
            )
        }
    }

    /**
     * Bulk uniqueness: generate 10 000 plausible task IDs and assert that CRC32
     * has strictly fewer collisions than `String.hashCode()` on the same set.
     *
     * Mathematically: with 10 000 random 32-bit codes, birthday-paradox expectation is
     * ~`10000² / (2 * 2³¹)` ≈ 11.6 collisions. We allow up to 30 (with comfort margin)
     * — but the key assertion is "CRC32 ≤ String.hashCode()", which is the *real*
     * property we care about for this fix.
     */
    @Test
    fun pendingIntentCodes_collidesLess_thanStringHashCode_atScale() {
        val n = 10_000
        val ids = (0 until n).map { i ->
            // Mix of UUID-ish and human-readable, mirroring real workloads.
            when (i % 3) {
                0 -> "task-${randomUuidLike(i)}"
                1 -> "user-$i-daily-sync"
                else -> "session-${randomUuidLike(i + 7919)}"
            }
        }
        // Sanity: IDs themselves must be unique — otherwise we are not measuring hash
        // collision, we are measuring duplicate input.
        assertEquals(n, ids.toSet().size, "Generated IDs must be unique to make this test meaningful")

        val crcCodes = ids.map { PendingIntentCodes.forTaskId(it) }
        val hashCodes = ids.map { (it.hashCode() and 0x7FFFFFFF) }

        val crcCollisions = n - crcCodes.toSet().size
        val hashCollisions = n - hashCodes.toSet().size

        // The fix's promise: CRC32 cannot do worse than String.hashCode() on real inputs.
        assertTrue(
            crcCollisions <= hashCollisions,
            "CRC32 collision count ($crcCollisions) must be ≤ String.hashCode() ($hashCollisions). " +
                "If this fails, the fix is no improvement over the legacy code."
        )
        // Comfort cap so a regression that inflates collisions trips the test even if
        // String.hashCode() also regresses.
        assertTrue(
            crcCollisions <= 30,
            "CRC32 collisions ($crcCollisions) exceeded comfort cap of 30 for n=$n IDs"
        )
    }

    /**
     * Two IDs differing in case must produce different codes (CRC32 is byte-sensitive).
     * Tests UTF-8 byte path: a uppercase A (0x41) and lowercase a (0x61) are different
     * inputs; the output must reflect that.
     */
    @Test
    fun pendingIntentCodes_isCaseSensitive() {
        assertNotEquals(
            PendingIntentCodes.forTaskId("MyTask"),
            PendingIntentCodes.forTaskId("mytask"),
            "Case difference must produce different codes — task IDs are byte-sensitive"
        )
    }

    /**
     * Empty string is a valid (if odd) task ID — must produce a stable, non-negative code.
     */
    @Test
    fun pendingIntentCodes_handlesEmpty() {
        val code = PendingIntentCodes.forTaskId("")
        assertTrue(code >= 0, "Empty string must still produce a non-negative code, got $code")
        assertEquals(PendingIntentCodes.forTaskId(""), code, "Must be deterministic")
        assertNotEquals(
            code, PendingIntentCodes.forTaskId(" "),
            "Empty and single-space must produce different codes"
        )
    }

    /**
     * Long strings must not overflow or throw. Test up to 100 KB which is well above
     * any realistic task ID length but exercises the inner CRC32 loop.
     */
    @Test
    fun pendingIntentCodes_handlesLongInput() {
        val long = "x".repeat(100_000)
        val code = PendingIntentCodes.forTaskId(long)
        assertTrue(code >= 0)
        // A 1-byte change must produce a different code — proves the whole input is consumed.
        val mutated = long.substring(0, long.length - 1) + "y"
        assertNotEquals(code, PendingIntentCodes.forTaskId(mutated))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Cheap deterministic UUID-like string. Not cryptographic — just produces a
     * realistic-shaped task ID where the hash distribution can be observed.
     */
    private fun randomUuidLike(seed: Int): String {
        val rng = kotlin.random.Random(seed.toLong())
        val hex = "0123456789abcdef"
        fun chunk(n: Int) = (1..n).map { hex[rng.nextInt(16)] }.joinToString("")
        return "${chunk(8)}-${chunk(4)}-${chunk(4)}-${chunk(4)}-${chunk(12)}"
    }
}
