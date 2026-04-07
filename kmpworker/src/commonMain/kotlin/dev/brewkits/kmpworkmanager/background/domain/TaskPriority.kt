package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.Serializable

/**
 * Execution priority for a background task.
 *
 * Controls how urgently the scheduler tries to run a task when multiple
 * tasks are competing for the available background execution budget.
 *
 * **Android mapping:**
 * - [CRITICAL] / [HIGH] → `setExpedited()` (runs as expedited work, bypasses Doze)
 * - [NORMAL] → standard OneTime / Periodic work
 * - [LOW] → standard work, deferred when battery/network is constrained
 *
 * **iOS mapping:**
 * - The in-memory queue is sorted by priority before each BGTask execution window.
 *   Higher-priority chains are dequeued and executed first.
 * - [CRITICAL] additionally sets the chain's `BGProcessingTask` to request
 *   network and external-power constraints to be relaxed (best-effort).
 *
 * **Usage:**
 * ```kotlin
 * scheduler.beginWith(
 *     TaskRequest(
 *         workerClassName = "PaymentSyncWorker",
 *         priority = TaskPriority.CRITICAL   // Must run ASAP
 *     )
 * ).enqueue()
 *
 * scheduler.beginWith(
 *     TaskRequest(
 *         workerClassName = "ThumbnailCacheWorker",
 *         priority = TaskPriority.LOW        // Fine to defer
 *     )
 * ).enqueue()
 * ```
 */
@Serializable
enum class TaskPriority(
    /** Numeric weight — higher = runs first. Used for queue sorting on iOS. */
    internal val weight: Int
) {
    /** Deferred work that can wait for idle/charging conditions. */
    LOW(weight = 0),

    /** Default priority for most background tasks. */
    NORMAL(weight = 1),

    /**
     * Important work that should run before NORMAL tasks.
     *
     * Android: mapped to expedited work (skips Doze, higher OS priority).
     */
    HIGH(weight = 2),

    /**
     * Mission-critical work (payments, security tokens, compliance uploads).
     *
     * Android: mapped to expedited work.
     * iOS: placed at head of execution queue, executed in the very first
     * available BGTask window.
     *
     * Use sparingly — overuse degrades the app's background execution budget.
     */
    CRITICAL(weight = 3)
}
