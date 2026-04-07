package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * A single persisted record of a background task chain execution.
 *
 * Records are written when a chain finishes (success, failure, abandoned, skipped)
 * and stored locally via [ExecutionHistoryStore]. Call [BackgroundTaskScheduler.getExecutionHistory]
 * to retrieve them and upload to your server when the app is foregrounded.
 *
 * ```kotlin
 * // Collect on app foreground, upload to backend
 * val records = scheduler.getExecutionHistory(limit = 200)
 * analyticsService.uploadBatchAsync(records)
 * ```
 */
@Serializable
data class ExecutionRecord(
    /** Unique ID for this record (UUID). */
    val id: String,
    /** Chain ID. Matches the ID passed to `TaskChain.withId()`, or auto-generated. */
    val chainId: String,
    /** Execution outcome. */
    val status: ExecutionStatus,
    /** Unix epoch ms when execution started. */
    val startedAtMs: Long,
    /** Unix epoch ms when execution ended. */
    val endedAtMs: Long,
    /** `endedAtMs - startedAtMs` in milliseconds. */
    val durationMs: Long,
    /** Total number of steps defined in the chain. */
    val totalSteps: Int,
    /** Number of steps that completed successfully before this record was written. */
    val completedSteps: Int,
    /** 0-based index of the step that caused failure, or null on success/skip. */
    val failedStep: Int? = null,
    /** Error message from the failing step, or null on success. */
    val errorMessage: String? = null,
    /** Chain retry count at the time this record was written. */
    val retryCount: Int = 0,
    /** "android" or "ios". */
    val platform: String,
    /** All worker class names in the chain, in step order (flattened). */
    val workerClassNames: List<String> = emptyList()
)

/**
 * Outcome of a chain execution.
 *
 * - **SUCCESS** — all steps completed successfully.
 * - **FAILURE** — a step failed; chain is re-queued for retry.
 * - **ABANDONED** — a step failed and max retries were exhausted, or the chain contained
 *   non-idempotent tasks after a corrupt-progress self-heal.
 * - **SKIPPED** — chain was discarded before execution (REPLACE policy or deadline exceeded).
 * - **TIMEOUT** — chain timed out within its BGTask window; re-queued for continuation.
 */
@Serializable
enum class ExecutionStatus {
    SUCCESS, FAILURE, ABANDONED, SKIPPED, TIMEOUT
}
