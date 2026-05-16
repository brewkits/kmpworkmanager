package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Result type for Worker execution.
 *
 * Three terminal states:
 * - [Success]: work completed; optional `data` flows to [TaskCompletionEvent.outputData].
 * - [Failure]: work failed permanently. By default no retry happens. The legacy
 *   [Failure.shouldRetry] flag is honored so existing callers do not break, but new
 *   code should prefer the explicit [Retry] variant — `Failure(shouldRetry = true)` only
 *   defers to the scheduler's default backoff and has no way to express a custom delay or
 *   an attempt cap.
 * - [Retry]: work should be retried after [Retry.delayMs] (or the scheduler default if
 *   null) up to [Retry.attemptCap] attempts (or unbounded if null). On Android this maps
 *   to `Result.retry()` and lets WorkManager apply its backoff policy; on iOS the chain
 *   executor will re-enqueue the chain step.
 *
 * Example:
 * ```kotlin
 * class DataFetchWorker : Worker {
 *     override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
 *         return try {
 *             val items = fetchData()
 *             WorkerResult.Success(
 *                 message = "Fetched ${items.size} items",
 *                 data = buildJsonObject {
 *                     put("count", items.size)
 *                     put("firstItem", items.firstOrNull()?.id ?: "")
 *                 }
 *             )
 *         } catch (e: TransientNetworkException) {
 *             WorkerResult.Retry(reason = "network: ${e.message}", delayMs = 30_000, attemptCap = 5)
 *         } catch (e: Exception) {
 *             WorkerResult.Failure("Failed: ${e.message}")
 *         }
 *     }
 * }
 * ```
 */
sealed class WorkerResult {
    /**
     * Represents successful worker execution.
     *
     * @param message Optional success message
     * @param data Optional output data to be passed to listeners via TaskCompletionEvent.
     *             Use [buildJsonObject] to construct: `buildJsonObject { put("key", value) }`
     */
    data class Success(
        val message: String? = null,
        val data: JsonObject? = null
    ) : WorkerResult()

    /**
     * Represents failed worker execution.
     *
     * @param message Error message describing the failure
     * @param shouldRetry Legacy retry hint. New code should return [Retry] instead — the
     *   boolean form cannot express a custom delay or attempt cap, and the scheduler has
     *   no way to differentiate "retry once after 30 s" from "retry forever immediately".
     */
    data class Failure(
        val message: String,
        val shouldRetry: Boolean = false
    ) : WorkerResult()

    /**
     * Represents a transient failure that should be retried.
     *
     * @param reason Human-readable explanation (logged + propagated to telemetry as an error
     *   message). Distinct from [Failure.message] only in intent: "we know this will probably
     *   succeed on retry."
     * @param delayMs Optional minimum delay before the next attempt, in milliseconds.
     *   - Android: passed to [WorkRequest] backoff (capped at WorkManager's 5-hour ceiling).
     *   - iOS: the chain executor re-enqueues the step with `earliestBeginDate = now + delayMs`.
     *   `null` means "use the scheduler default" (the constraint's backoff policy on Android,
     *   ~1 minute on iOS).
     * @param attemptCap Optional maximum number of attempts including the original. `null`
     *   means unbounded — the scheduler's own quota governs the upper bound. Setting `1`
     *   is equivalent to [Failure] without retry and is rejected at construction to surface
     *   the bug rather than silently never retrying.
     */
    data class Retry(
        val reason: String,
        val delayMs: Long? = null,
        val attemptCap: Int? = null
    ) : WorkerResult() {
        init {
            if (delayMs != null) {
                require(delayMs >= 0) { "delayMs must be >= 0, got $delayMs" }
            }
            if (attemptCap != null) {
                require(attemptCap >= 2) {
                    "attemptCap must be >= 2 (the original attempt + at least one retry). " +
                        "Use WorkerResult.Failure if you do not want any retry."
                }
            }
        }
    }
}
