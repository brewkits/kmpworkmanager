package dev.brewkits.kmpworkmanager.background.domain

/**
 * Main interface for scheduling background tasks.
 * Use this from your common code - it works on both Android and iOS.
 */
interface BackgroundTaskScheduler {
    /**
     * Enqueues a task to be executed in the background.
     *
     * @param id A unique identifier for the task, used for cancellation and replacement.
     * @param trigger The condition that will trigger the task execution.
     * @param workerClassName A unique name identifying the actual work (Worker/Job) to be done on the platform.
     * @param constraints Conditions that must be met for the task to run. Defaults to no constraints.
     * @param inputJson Optional JSON string data to pass as input to the worker. Defaults to null.
     *   **10 KB limit** (enforced at call site — see [BackgroundTaskSchedulerExt.enqueue]).
     *   This limit mirrors Android WorkManager's `Data` cap and applies to iOS as well for
     *   cross-platform consistency.
     *
     *   **Passing large or binary data (images, audio, etc.)**:
     *   Do NOT embed raw bytes or Base64-encoded payloads in `inputJson`. Background task
     *   schedulers are designed to carry lightweight _references_, not _data_. Instead:
     *   1. Write the data to a known location (app cache dir, shared container) **before** enqueuing.
     *   2. Pass the file path or a stable identifier as part of `inputJson`.
     *   3. The worker reads the file, processes it, and writes output to another path.
     *   4. Return the output path in [WorkerResult.Success.data] for the next step or caller.
     *
     *   ```kotlin
     *   // ✅ Correct — pass a reference, not the bytes
     *   val tempPath = cacheDir.resolve("upload_$id.jpg").also { it.writeBytes(photoBytes) }
     *   scheduler.enqueue(
     *       id = "compress-$id",
     *       trigger = TaskTrigger.OneTime(),
     *       workerClassName = "ImageCompressWorker",
     *       inputJson = buildJsonObject { put("sourcePath", tempPath.absolutePath) }.toString()
     *   )
     *   ```
     *
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
     * **Breaking Change:** This method is now suspending to prevent deadlock risks.
     * Previously used `runBlocking` which could cause deadlocks under load.
     *
     * Migration:
     * ```kotlin
     * // Before (v2.3.x):
     * val chain = scheduler.beginWith(task).then(task2)
     * chain.enqueue()  // Blocking call
     *
     * // After:
     * val chain = scheduler.beginWith(task).then(task2)
     * chain.enqueue()  // Now suspending - call from coroutine
     * ```
     *
     * @param chain The task chain to enqueue
     * @param id Unique identifier for the chain (optional, auto-generated if not provided)
     * @param policy How to handle if a chain with the same ID already exists
     * Now suspending to prevent deadlock risks
     */
    suspend fun enqueueChain(
        chain: TaskChain,
        id: String? = null,
        policy: ExistingPolicy = ExistingPolicy.REPLACE
    )

    /**
     * Flush all pending progress updates to disk immediately.
     * Added to prevent data loss when your app goes to the background.
     *
     * **When to use:**
     * - iOS: Call from `applicationWillResignActive` in AppDelegate
     * - Android: Optional (WorkManager handles this automatically)
     * - Before BGTask expiration
     * - Before app termination
     *
     * **iOS implementation details:**
     * - I/O is dispatched to a background thread (`Dispatchers.Default`) — the Main
     *   Thread is never blocked by disk I/O directly.
     * - The flush is bounded to **500 ms** maximum via `withTimeoutOrNull`. If the
     *   queue is unusually large, a partial flush is accepted with a warning log rather
     *   than risking an iOS Watchdog kill (~1 s budget for `applicationWillResignActive`).
     *
     * **Recommended Swift pattern (maximum safety):**
     * Wrap the call in `UIApplication.beginBackgroundTask` to request up to 30 s of
     * extra OS execution time. This ensures the flush survives even if the OS aggressively
     * suspends the app right after `applicationWillResignActive` returns.
     *
     * ```swift
     * // In AppDelegate.swift:
     * func applicationWillResignActive(_ application: UIApplication) {
     *     var bgTaskId: UIBackgroundTaskIdentifier = .invalid
     *     bgTaskId = UIApplication.shared.beginBackgroundTask {
     *         UIApplication.shared.endBackgroundTask(bgTaskId)
     *         bgTaskId = .invalid
     *     }
     *     KmpWorkManager.shared.backgroundTaskScheduler.flushPendingProgress()
     *     UIApplication.shared.endBackgroundTask(bgTaskId)
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
     */
    fun flushPendingProgress()

    /**
     * Returns the most recent task chain execution records, newest first.
     *
     * Records are persisted locally after each chain execution (iOS) or individual
     * task execution (Android). Use this to collect telemetry when the app returns
     * to the foreground and upload to your analytics backend.
     *
     * ```kotlin
     * // In Activity.onResume() or app foreground observer:
     * lifecycleScope.launch {
     *     val records = scheduler.getExecutionHistory(limit = 200)
     *     if (records.isNotEmpty()) {
     *         analyticsService.uploadBatch(records)
     *         scheduler.clearExecutionHistory()
     *     }
     * }
     * ```
     *
     * @param limit Maximum number of records to return. Defaults to 100.
     * @return Records sorted newest-first. Empty list if no history or store not initialized.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord>

    /**
     * Deletes all stored execution history records.
     * Call after successfully uploading records to free disk space.
     */
    suspend fun clearExecutionHistory()
}