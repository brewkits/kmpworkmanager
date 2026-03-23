package dev.brewkits.kmpworkmanager.background.domain

/**
 * Main interface for scheduling background tasks.
 * Use this from your common code - it works on both Android and iOS.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a task to be executed in the background.
     * @param id A unique identifier for the task, used for cancellation and replacement.
     * @param trigger The condition that will trigger the task execution.
     * @param workerClassName A unique name identifying the actual work (Worker/Job) to be done on the platform.
     * @param constraints Conditions that must be met for the task to run. Defaults to no constraints.
     * @param inputJson Optional JSON string data to pass as input to the worker. Defaults to null.
     * @param policy How to handle this request if a task with the same ID already exists. Defaults to REPLACE.
     * @return The result of the scheduling operation (ACCEPTED, REJECTED, THROTTLED).
     */
    suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints = Constraints(),
        inputJson: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    ): ScheduleResult

    /** Cancels a specific pending task by its unique ID. */
    fun cancel(id: String)

    /** Cancels all previously scheduled tasks currently managed by the scheduler. */
    fun cancelAll()

    /**
     * Begins a new task chain with a single initial task.
     * @param task The first [TaskRequest] in the chain.
     * @return A [TaskChain] builder instance to append more tasks.
     */
    fun beginWith(task: TaskRequest): TaskChain

    /**
     * Begins a new task chain with a group of tasks that will run in parallel.
     * @param tasks A list of [TaskRequest]s to run in parallel as the first step.
     * @return A [TaskChain] builder instance to append more tasks.
     */
    fun beginWith(tasks: List<TaskRequest>): TaskChain

    /**
     * Enqueues a constructed [TaskChain] for execution.
     * This method is intended to be called from `TaskChain.enqueue()`.
     *
     * **Breaking Change (v2.3.5):** This method is now suspending to prevent deadlock risks.
     * Previously used `runBlocking` which could cause deadlocks under load.
     *
     * Migration:
     * ```kotlin
     * // Before (v2.3.x):
     * val chain = scheduler.beginWith(task).then(task2)
     * chain.enqueue()  // Blocking call
     *
     * // After (v2.3.5+):
     * val chain = scheduler.beginWith(task).then(task2)
     * chain.enqueue()  // Now suspending - call from coroutine
     * ```
     *
     * @param chain The task chain to enqueue
     * @param id Unique identifier for the chain (optional, auto-generated if not provided)
     * @param policy How to handle if a chain with the same ID already exists
     * @since 2.3.5 Now suspending to prevent deadlock risks
     */
    suspend fun enqueueChain(
        chain: TaskChain,
        id: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    )

    /**
     * Flush all pending progress updates to disk immediately.
     * Added in v2.3.5 to prevent data loss when your app goes to the background.
     *
     * **When to use:**
     * - iOS: Call from `applicationWillResignActive` in AppDelegate
     * - Android: Optional (WorkManager handles this automatically)
     * - Before BGTask expiration
     * - Before app termination
     *
     * **iOS implementation details:**
     * - I/O runs on a background thread (not Main Thread) to prevent UI jank
     * - Bounded to **500 ms** maximum — safely within the iOS Watchdog limit for
     *   `applicationWillResignActive` (~1 s). If the queue is very large and the
     *   flush cannot complete in time, a partial flush is accepted with a warning log.
     *
     * **Benefits:**
     * - No Main Thread I/O — Watchdog-safe even with large progress queues
     * - Guarantees no progress data is lost when iOS suspends your app
     *
     * **Example (iOS):**
     * ```swift
     * // In AppDelegate.swift:
     * func applicationWillResignActive(_ application: UIApplication) {
     *     KmpWorkManager.shared.backgroundTaskScheduler.flushPendingProgress()
     * }
     * ```
     *
     * **Example Usage (Android):**
     * ```kotlin
     * // In critical Activity:
     * override fun onPause() {
     *     super.onPause()
     *     KmpWorkManager.getInstance().backgroundTaskScheduler.flushPendingProgress()
     * }
     * ```
     *
     * @since 2.3.4
     */
    fun flushPendingProgress()
}