package dev.brewkits.kmpworkmanager.utils

import kotlin.random.Random

/**
 * Spreads retry backoff so that devices which failed together do not retry together.
 *
 * **The problem this exists for.** Both platforms computed backoff deterministically:
 * `DynamicTaskDispatcher.computeBackoffDelayMs` multiplied a fixed base by the attempt
 * number, and Android handed the same fixed base to `WorkRequest.setBackoffCriteria`, whose
 * own math is equally deterministic. So a backend outage that failed 100 000 installs inside
 * the same second produced 100 000 retries in the same second, 30 s later — and again, in
 * lockstep, at every subsequent attempt. The library's own retry policy turned one outage
 * into a self-inflicted load test on the customer's recovering backend.
 *
 * **Equal jitter**, not full jitter. The returned delay is uniform in `[delay/2, delay]`:
 * - it never exceeds the computed delay, so an existing cap (1 h on iOS, WorkManager's own
 *   ceiling on Android) still holds without a second clamp;
 * - it never drops below half, so backoff keeps meaning what the caller configured. Full
 *   jitter (uniform in `[0, delay]`) spreads slightly better but can retry almost
 *   immediately, which for a background-task library reads as the backoff being ignored.
 *
 * A ±50% window over a 30 s base spreads a herd across 15 s. That is enough to turn a spike
 * into a ramp; it is not meant to be a rate limiter.
 */
internal object BackoffJitter {

    /**
     * @param delayMs the deterministic delay to spread. Zero or negative is returned as-is —
     *   a caller asking for no delay is not asking for a random one.
     * @param random injectable for tests; production always uses the shared generator.
     */
    fun apply(delayMs: Long, random: Random = Random.Default): Long {
        if (delayMs <= 0L) return delayMs
        val half = delayMs / 2
        return half + random.nextLong(half + 1)
    }
}
