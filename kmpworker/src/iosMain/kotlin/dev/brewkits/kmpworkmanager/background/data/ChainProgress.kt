package dev.brewkits.kmpworkmanager.background.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Tracks the execution progress of a task chain on iOS.
 *
 * When a BGTask is interrupted (timeout, force-quit, etc.), this model
 * allows resuming the chain from where it left off instead of restarting
 * from the beginning.
 *
 * **Use Case:**
 * ```
 * Chain: [Step0, Step1, Step2, Step3, Step4]
 * - Execution starts, Step0 and Step1 complete successfully
 * - BGTask times out during Step2
 * - On next BGTask, resume from Step2 instead of Step0
 * ```
 *
 * **Retry Logic:**
 * - If a step fails, increment retryCount
 * - If retryCount >= maxRetries, abandon the chain
 * - This prevents infinite retry loops for permanently failing chains
 *
 * **Schema versioning:**
 * The [schemaVersion] field guards against data loss during upgrades. If the binary
 * structure of this class changes (field type changes, removals, renames), increment
 * [CURRENT_SCHEMA_VERSION] and add a migration branch in
 * `IosFileStorage.loadChainProgress`. Using `ignoreUnknownKeys = true` handles
 * *additive* changes (new nullable/default fields) without any migration. Only
 * *breaking* changes (type changes, removals) need an explicit migration.
 *
 * @property schemaVersion Data schema version — used for migration on upgrade
 * @property chainId Unique identifier for the chain
 * @property totalSteps Total number of steps in the chain
 * @property completedSteps Indices of successfully completed steps (e.g., [0, 1])
 * @property completedTasksInSteps Per-step tracking of which parallel task indices
 *   completed successfully. Keyed by step index; values are sorted task indices.
 *   Cleared for a step once that step is marked fully completed.
 * @property lastFailedStep Index of the step that last failed, if any
 * @property lastError Error message from the last failed task, if any
 * @property retryCount Number of times this chain has been retried due to task failures
 * @property maxRetries Maximum retry attempts before abandoning (default: 3)
 * @property crashAttemptCount Number of BGTask invocations that started executing this
 *   chain but never finished cleanly (process killed, OOM, native crash). Incremented on
 *   disk BEFORE any work begins so it survives process death. When this exceeds
 *   [MAX_CRASH_ATTEMPTS] the chain is quarantined to break the crash loop.
 */
@Serializable
data class ChainProgress(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val chainId: String,
    val totalSteps: Int,
    val completedSteps: List<Int> = emptyList(),
    val completedTasksInSteps: Map<Int, List<Int>> = emptyMap(),
    val lastFailedStep: Int? = null,
    val lastError: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val crashAttemptCount: Int = 0
) {
    companion object {
        /**
         * Increment this when making a **breaking** change to [ChainProgress] fields
         * (type change, removal, rename). Additive changes (new fields with defaults)
         * do NOT need a version bump — [ignoreUnknownKeys] handles those automatically.
         *
         * Migration history:
         *  v1 — initial release
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * Maximum number of BGTask invocations that can start a chain without it ever
         * completing cleanly before the chain is considered a "poison pill".
         *
         * A chain that repeatedly crashes the process (OOM, native exception) would
         * cause the app to be penalised by iOS (no more background time) if it crashes
         * every BGTask invocation. This guard quarantines the chain after N crashes so
         * subsequent BGTask invocations are not poisoned by the same chain.
         */
        const val MAX_CRASH_ATTEMPTS = 5
    }
    /**
     * Check if a specific step has been completed.
     */
    fun isStepCompleted(stepIndex: Int): Boolean {
        return stepIndex in completedSteps
    }

    /**
     * Check if a specific task within a parallel step has already completed.
     * Used to skip succeeded tasks when retrying a partially-failed step.
     */
    fun isTaskInStepCompleted(stepIndex: Int, taskIndex: Int): Boolean {
        return taskIndex in (completedTasksInSteps[stepIndex] ?: emptyList())
    }

    /**
     * Record that a single task within a parallel step completed successfully.
     */
    fun withCompletedTaskInStep(stepIndex: Int, taskIndex: Int): ChainProgress {
        val currentTasks = completedTasksInSteps[stepIndex] ?: emptyList()
        if (taskIndex in currentTasks) return this
        return copy(
            completedTasksInSteps = completedTasksInSteps + (stepIndex to (currentTasks + taskIndex).sorted())
        )
    }

    /**
     * Get the index of the next step to execute.
     * Returns null if all steps are completed.
     */
    fun getNextStepIndex(): Int? {
        for (i in 0 until totalSteps) {
            if (!isStepCompleted(i)) {
                return i
            }
        }
        return null // All steps completed
    }

    /**
     * Create a new progress with an additional completed step.
     */
    fun withCompletedStep(stepIndex: Int): ChainProgress {
        if (isStepCompleted(stepIndex)) {
            return this // Already completed
        }

        return copy(
            completedSteps = (completedSteps + stepIndex).sorted(),
            completedTasksInSteps = completedTasksInSteps - stepIndex, // Per-task data no longer needed
            lastFailedStep = null // Clear failure on success
        )
    }

    /**
     * Create a new progress with an incremented retry count and optional error message.
     * Used for logical failures (e.g., network error, worker exception).
     */
    fun withFailure(stepIndex: Int, errorMessage: String? = null): ChainProgress {
        return copy(
            lastFailedStep = stepIndex,
            lastError = errorMessage,
            retryCount = retryCount + 1
        )
    }

    /**
     * Create a new progress due to an OS-enforced timeout.
     *
     * **Sustainability fix:** Timeout does NOT increment [retryCount].
     * A long chain that naturally exceeds the 300s iOS BGTask window should be allowed
     * to resume as many times as needed to finish, provided each step eventually
     * succeeds. Only actual task failures (exceptions) count towards abandonment.
     */
    fun withTimeout(stepIndex: Int, errorMessage: String? = null): ChainProgress {
        return copy(
            lastFailedStep = stepIndex,
            lastError = errorMessage
            // retryCount remains UNCHANGED
        )
    }

    /**
     * Check if the chain has exceeded max retries.
     */
    fun hasExceededRetries(): Boolean {
        return retryCount >= maxRetries
    }

    /**
     * Returns a copy with [crashAttemptCount] incremented by one.
     *
     * Call this BEFORE any work begins and persist to disk immediately so the counter
     * survives a process kill. If the chain completes (success or clean failure), its
     * progress file is deleted anyway; the count only matters across process-death cycles.
     */
    fun withCrashAttempt(): ChainProgress = copy(crashAttemptCount = crashAttemptCount + 1)

    /**
     * Returns true when this chain has crashed enough times to be considered a poison pill.
     * A chain that consistently crashes the process should be quarantined so it does not
     * penalise the app's iOS background execution budget on every BGTask invocation.
     */
    fun isPoisonPill(): Boolean = crashAttemptCount >= MAX_CRASH_ATTEMPTS

    /**
     * Check if all steps are completed.
     */
    fun isComplete(): Boolean {
        return completedSteps.size == totalSteps
    }

    /**
     * Get completion percentage (0-100).
     */
    fun getCompletionPercentage(): Int {
        if (totalSteps == 0) return 100
        return (completedSteps.size * 100) / totalSteps
    }
}
