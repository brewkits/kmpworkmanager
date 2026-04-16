@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalObjCName::class
)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.*

/**
 * iOS background task execution handler — the Kotlin-native API that replaces Swift boilerplate.
 *
 * Previously, every host app had to copy ~150 lines of Swift helper methods
 * (`handleSingleTask`, `handleChainExecutorTask`) from the demo app. This object provides
 * those same lifecycle functions as a first-class library API, reading metadata directly from
 * the library's file-based storage instead of the legacy UserDefaults format.
 *
 * ## Usage from Swift
 *
 * ### Single tasks (BGAppRefreshTask / BGProcessingTask)
 * ```swift
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "my-sync-task", using: nil) { task in
 *     IosBackgroundTaskHandler.shared.handleSingleTask(
 *         task: task,
 *         scheduler: koinIos.getScheduler(),
 *         executor: myTaskExecutor  // SingleTaskExecutor instance
 *     )
 * }
 * ```
 *
 * ### Chain executor (BGProcessingTask — must use identifier "kmp_chain_executor_task")
 * ```swift
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "kmp_chain_executor_task", using: nil) { task in
 *     IosBackgroundTaskHandler.shared.handleChainExecutorTask(
 *         task: task,
 *         chainExecutor: myChainExecutor  // ChainExecutor instance
 *     )
 * }
 * ```
 *
 * ### Koin integration (typical setup)
 * ```kotlin
 * // iosMain — expose executors via KoinIOS
 * class KoinIOS : KoinComponent {
 *     private val scheduler: BackgroundTaskScheduler by inject()
 *     private val executor: SingleTaskExecutor by inject()
 *     private val chainExecutor: ChainExecutor by inject()
 *
 *     fun getScheduler() = scheduler
 *     fun getExecutor() = executor
 *     fun getChainExecutor() = chainExecutor
 * }
 * ```
 * ```swift
 * // Swift — wire BGTaskScheduler registrations to the handler
 * let koin = KoinIOS()
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "my-sync-task", using: nil) { task in
 *     IosBackgroundTaskHandler.shared.handleSingleTask(
 *         task: task,
 *         scheduler: koin.getScheduler(),
 *         executor: koin.getExecutor()
 *     )
 * }
 * ```
 *
 * ## What this handles for you
 * - BGTask `expirationHandler` setup
 * - Worker metadata lookup from file storage (workerClassName, inputJson, isPeriodic, etc.)
 * - Worker execution with timeout protection via [SingleTaskExecutor]
 * - Auto-rescheduling of periodic tasks after successful execution
 * - `task.setTaskCompleted(success:)` call for both success and failure paths
 * - Chain queue re-scheduling when more chains remain after a batch
 *
 * @see SingleTaskExecutor
 * @see ChainExecutor
 */
@kotlin.native.ObjCName("IosBackgroundTaskHandler")
object IosBackgroundTaskHandler {

    /**
     * Shared instance for Swift access.
     */
    val shared: IosBackgroundTaskHandler get() = this

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Handles a single (non-chain) background task received from BGTaskScheduler.
     *
     * Resolves the registered worker from file storage, executes it via [executor],
     * re-schedules if the task is periodic, then calls `task.setTaskCompleted`.
     *
     * @param task The [BGTask] received in the BGTaskScheduler registration closure.
     * @param scheduler The [BackgroundTaskScheduler] (must be [NativeTaskScheduler] on iOS).
     *   Used to re-schedule periodic tasks after execution.
     * @param executor The [SingleTaskExecutor] that runs the worker inside a coroutine
     *   with timeout protection.
     */
    fun handleSingleTask(
        task: BGTask,
        scheduler: BackgroundTaskScheduler,
        executor: SingleTaskExecutor
    ) {
        val nativeScheduler = scheduler as? NativeTaskScheduler ?: run {
            Logger.e(
                LogTags.SCHEDULER,
                "handleSingleTask: scheduler must be NativeTaskScheduler on iOS. " +
                    "Received: ${scheduler::class.simpleName}"
            )
            task.setTaskCompletedWithSuccess(false)
            return
        }

        val taskId = task.identifier
        Logger.i(LogTags.SCHEDULER, "handleSingleTask: '$taskId'")

        // expirationHandler is called synchronously by iOS when time runs out.
        // It must complete instantly — no coroutines or I/O here.
        task.expirationHandler = {
            Logger.w(LogTags.SCHEDULER, "Task '$taskId' expired — marking failed")
            task.setTaskCompletedWithSuccess(false)
        }

        val meta = resolveTaskMetadata(taskId, nativeScheduler.fileStorage) ?: run {
            Logger.e(LogTags.SCHEDULER, "No metadata found for task '$taskId' — cannot execute")
            task.setTaskCompletedWithSuccess(false)
            return
        }

        // Deadline check for windowed tasks
        val windowLatest = meta.rawMeta?.get("windowLatest")?.toDoubleOrNull()
        if (windowLatest != null) {
            val nowMs = NSDate().timeIntervalSince1970 * 1000.0
            if (nowMs > windowLatest) {
                val overdueSeconds = ((nowMs - windowLatest) / 1000.0).toInt()
                Logger.w(
                    LogTags.SCHEDULER,
                    "DEADLINE_MISSED — Windowed task '$taskId' ran ${overdueSeconds}s past its 'latest' deadline. " +
                        "Skipping worker execution to prevent stale work."
                )
                task.setTaskCompletedWithSuccess(false)
                return
            }
        }

        scope.launch {
            try {
                val result = executor.executeTask(meta.workerClassName, meta.inputJson)
                val success = result is WorkerResult.Success

                if (meta.isPeriodic) {
                    reschedulePeriodicTask(
                        taskId = taskId,
                        workerClassName = meta.workerClassName,
                        inputJson = meta.inputJson,
                        rawMeta = meta.rawMeta,
                        scheduler = scheduler
                    )
                }

                Logger.i(LogTags.SCHEDULER, "Task '$taskId' finished (success=$success)")
                task.setTaskCompletedWithSuccess(success)
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Task '$taskId' threw exception", e)
                task.setTaskCompletedWithSuccess(false)
            }
        }
    }

    /**
     * Handles the KMP chain executor batch task received from BGTaskScheduler.
     *
     * Wires the BGTask expiration handler to [ChainExecutor.requestShutdownSync],
     * resets shutdown state from prior invocations, runs [ChainExecutor.executeChainsInBatch],
     * re-schedules the chain executor if more chains remain, then calls `task.setTaskCompleted`.
     *
     * The task identifier for this handler must be `"kmp_chain_executor_task"` and must be
     * listed in `Info.plist → BGTaskSchedulerPermittedIdentifiers`.
     *
     * @param task The [BGTask] received in the BGTaskScheduler registration closure.
     * @param chainExecutor The [ChainExecutor] that processes the pending chain queue.
     */
    fun handleChainExecutorTask(
        task: BGTask,
        chainExecutor: ChainExecutor
    ) {
        Logger.i(LogTags.CHAIN, "handleChainExecutorTask: batch execution starting")

        // expirationHandler must complete instantly — use sync shutdown (no coroutine).
        task.expirationHandler = {
            Logger.w(LogTags.CHAIN, "Chain executor task expired — sync shutdown")
            chainExecutor.requestShutdownSync()
        }

        scope.launch {
            try {
                chainExecutor.resetShutdownState()
                val count = chainExecutor.executeChainsInBatch(
                    maxChains = 3,
                    totalTimeoutMs = 50_000L
                )
                Logger.i(LogTags.CHAIN, "Batch execution done — $count chain(s) processed")
                task.setTaskCompletedWithSuccess(true)
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "Chain executor task failed", e)
                task.setTaskCompletedWithSuccess(false)
            }
            rescheduleChainExecutorIfNeeded(chainExecutor)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers (internal for testability from iosTest source set)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Resolved task metadata from [IosFileStorage].
     */
    internal data class TaskMeta(
        val workerClassName: String,
        val inputJson: String?,
        val isPeriodic: Boolean,
        /** Full raw map — needed for periodic re-schedule parameters. */
        val rawMeta: Map<String, String>?
    )

    /**
     * Reads task metadata from file storage.
     *
     * Checks periodic storage first (isPeriodic=true), then falls back to one-time storage.
     * Returns null if no metadata exists or if workerClassName is blank.
     */
    internal fun resolveTaskMetadata(taskId: String, storage: IosFileStorage): TaskMeta? {
        val periodicMeta = storage.loadTaskMetadata(taskId, periodic = true)
        if (periodicMeta?.get("isPeriodic") == "true") {
            val workerClassName = periodicMeta["workerClassName"]
            if (workerClassName.isNullOrEmpty()) {
                Logger.e(LogTags.SCHEDULER, "Periodic task '$taskId' has no workerClassName in storage")
                return null
            }
            return TaskMeta(
                workerClassName = workerClassName,
                inputJson = periodicMeta["inputJson"]?.takeIf { it.isNotEmpty() },
                isPeriodic = true,
                rawMeta = periodicMeta
            )
        }

        val taskMeta = storage.loadTaskMetadata(taskId, periodic = false) ?: return null
        val workerClassName = taskMeta["workerClassName"]
        if (workerClassName.isNullOrEmpty()) {
            Logger.e(LogTags.SCHEDULER, "One-time task '$taskId' has no workerClassName in storage")
            return null
        }
        return TaskMeta(
            workerClassName = workerClassName,
            inputJson = taskMeta["inputJson"]?.takeIf { it.isNotEmpty() },
            isPeriodic = false,
            rawMeta = taskMeta
        )
    }

    /**
     * Re-schedules a periodic task after execution.
     * Uses drift-corrected scheduling via [BackgroundTaskScheduler.enqueue].
     */
    internal suspend fun reschedulePeriodicTask(
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        rawMeta: Map<String, String>?,
        scheduler: BackgroundTaskScheduler
    ) {
        val intervalMs = rawMeta?.get("intervalMs")?.toLongOrNull() ?: run {
            Logger.e(LogTags.SCHEDULER, "Periodic task '$taskId' missing intervalMs — cannot reschedule")
            return
        }
        val requiresNetwork = rawMeta["requiresNetwork"] == "true"
        val requiresCharging = rawMeta["requiresCharging"] == "true"
        val isHeavyTask = rawMeta["isHeavyTask"] == "true"

        try {
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.Periodic(intervalMs),
                workerClassName = workerClassName,
                constraints = Constraints(
                    requiresNetwork = requiresNetwork,
                    requiresCharging = requiresCharging,
                    isHeavyTask = isHeavyTask
                ),
                inputJson = inputJson,
                policy = ExistingPolicy.REPLACE
            )
            Logger.i(LogTags.SCHEDULER, "Rescheduled periodic task '$taskId' (every ${intervalMs}ms)")
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "Failed to reschedule periodic task '$taskId'", e)
        }
    }

    private suspend fun rescheduleChainExecutorIfNeeded(chainExecutor: ChainExecutor) {
        val remaining = chainExecutor.getChainQueueSize()
        if (remaining <= 0) {
            Logger.i(LogTags.CHAIN, "Chain queue empty — no reschedule needed")
            return
        }

        Logger.i(LogTags.CHAIN, "$remaining chain(s) remaining — rescheduling chain executor task")

        // In test environment (no app bundle), BGTaskScheduler is unavailable — skip submission.
        if (platform.Foundation.NSBundle.mainBundle.bundleIdentifier == null) {
            Logger.d(LogTags.CHAIN, "Test mode: skipping BGTaskScheduler submission")
            return
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val request = BGProcessingTaskRequest("kmp_chain_executor_task")
            request.earliestBeginDate = NSDate()
            request.requiresNetworkConnectivity = true
            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)
            if (!ok) {
                val err = errorPtr.value
                Logger.e(
                    LogTags.CHAIN,
                    "Failed to reschedule chain executor: ${err?.localizedDescription}"
                )
            }
        }
    }
}
