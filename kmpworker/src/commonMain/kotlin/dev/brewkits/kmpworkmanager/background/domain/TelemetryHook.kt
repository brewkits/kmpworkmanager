package dev.brewkits.kmpworkmanager.background.domain

/**
 * Hook interface for observing task lifecycle events.
 *
 * Implement this interface to route KMP WorkManager events to your telemetry
 * backend (Sentry, Firebase Crashlytics, Datadog, custom analytics, etc.).
 *
 * All methods have default no-op implementations — override only what you need.
 *
 * **Registration:**
 * ```kotlin
 * val config = KmpWorkManagerConfig(
 *     telemetryHook = object : TelemetryHook {
 *         override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
 *             Sentry.captureMessage("Task failed: ${event.taskName} — ${event.error}")
 *         }
 *         override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
 *             FirebaseCrashlytics.getInstance().recordException(
 *                 RuntimeException("Chain ${event.chainId} failed at step ${event.failedStep}")
 *             )
 *         }
 *     }
 * )
 * ```
 *
 * **Thread safety:** All callbacks are invoked from background coroutine dispatchers.
 * Implementations must be thread-safe and must NOT suspend or block (use fire-and-forget
 * if async work is needed: `CoroutineScope(Dispatchers.IO).launch { ... }`).
 */
interface TelemetryHook {

    // ── Task-level events ────────────────────────────────────────────────────

    /**
     * Called immediately before a worker's [Worker.doWork] is invoked.
     *
     * Use this to start a performance span or increment a "running" counter.
     */
    fun onTaskStarted(event: TaskStartedEvent) {}

    /**
     * Called after a worker completes — whether success or failure.
     *
     * Use this to record duration, success rate, or close a performance span.
     */
    fun onTaskCompleted(event: TaskCompletedEvent) {}

    /**
     * Called when a worker returns [WorkerResult.Failure] or throws an exception.
     *
     * Use this to send error reports to Sentry / Crashlytics.
     */
    fun onTaskFailed(event: TaskFailedEvent) {}

    // ── Chain-level events ───────────────────────────────────────────────────

    /**
     * Called when all steps in a chain finish successfully.
     */
    fun onChainCompleted(event: ChainCompletedEvent) {}

    /**
     * Called when a chain step fails and the chain is abandoned or re-queued.
     */
    fun onChainFailed(event: ChainFailedEvent) {}

    /**
     * Called when a chain is skipped due to a passed deadline or REPLACE policy.
     */
    fun onChainSkipped(event: ChainSkippedEvent) {}

    // ── Event data classes ───────────────────────────────────────────────────

    data class TaskStartedEvent(
        /** Worker class name (e.g. "HttpRequestWorker"). */
        val taskName: String,
        /** Chain ID this task belongs to, or null for standalone tasks. */
        val chainId: String? = null,
        /** 0-based step index within the chain, or null for standalone tasks. */
        val stepIndex: Int? = null,
        /** Platform: "android" or "ios". */
        val platform: String,
        /** Unix epoch ms when the task was started. */
        val startedAtMs: Long
    )

    data class TaskCompletedEvent(
        val taskName: String,
        val chainId: String? = null,
        val stepIndex: Int? = null,
        val platform: String,
        /** true = WorkerResult.Success, false = WorkerResult.Failure. */
        val success: Boolean,
        /** Wall-clock duration of doWork() in milliseconds. */
        val durationMs: Long,
        /** WorkerResult.Failure.message, or null on success. */
        val errorMessage: String? = null
    )

    data class TaskFailedEvent(
        val taskName: String,
        val chainId: String? = null,
        val stepIndex: Int? = null,
        val platform: String,
        val error: String,
        val durationMs: Long,
        /** Number of times this chain has been retried so far. */
        val retryCount: Int
    )

    data class ChainCompletedEvent(
        val chainId: String,
        val totalSteps: Int,
        val platform: String,
        /** Wall-clock duration from first task start to last task end, in ms. */
        val durationMs: Long
    )

    data class ChainFailedEvent(
        val chainId: String,
        /** 0-based index of the step that caused the failure. */
        val failedStep: Int,
        val platform: String,
        val error: String,
        val retryCount: Int,
        /** true = chain will be re-queued for retry, false = permanently abandoned. */
        val willRetry: Boolean
    )

    data class ChainSkippedEvent(
        val chainId: String,
        val platform: String,
        /** Human-readable reason: "DEADLINE_EXCEEDED", "REPLACE_POLICY", etc. */
        val reason: String
    )
}
