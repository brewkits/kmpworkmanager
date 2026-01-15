package dev.brewkits.kmpworkmanager.background.data

import kotlinx.serialization.Serializable

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
 * @property chainId Unique identifier for the chain
 * @property totalSteps Total number of steps in the chain
 * @property completedSteps Indices of successfully completed steps (e.g., [0, 1])
 * @property lastFailedStep Index of the step that last failed, if any
 * @property retryCount Number of times this chain has been retried
 * @property maxRetries Maximum retry attempts before abandoning (default: 3)
 */
@Serializable
data class ChainProgress(
    val chainId: String,
    val totalSteps: Int,
    val completedSteps: List<Int> = emptyList(),
    val lastFailedStep: Int? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
) {
    /**
     * Check if a specific step has been completed.
     */
    fun isStepCompleted(stepIndex: Int): Boolean {
        return stepIndex in completedSteps
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
            lastFailedStep = null // Clear failure on success
        )
    }

    /**
     * Create a new progress with an incremented retry count.
     */
    fun withFailure(stepIndex: Int): ChainProgress {
        return copy(
            lastFailedStep = stepIndex,
            retryCount = retryCount + 1
        )
    }

    /**
     * Check if the chain has exceeded max retries.
     */
    fun hasExceededRetries(): Boolean {
        return retryCount >= maxRetries
    }

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
