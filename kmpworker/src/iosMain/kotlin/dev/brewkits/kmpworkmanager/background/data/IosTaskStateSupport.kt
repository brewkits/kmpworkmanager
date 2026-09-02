package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskState

/**
 * Computes a [TaskState] snapshot for [id] on iOS, given the three independent sources of
 * truth this library has for "what is currently going on with this id" — no live registry
 * is threaded through the executors for this (see [NativeTaskScheduler.observeTaskState]'s
 * KDoc for why: iOS has no OS-level "is this executing right now" API to make one
 * authoritative anyway, and retrofitting one into `ChainExecutor`/`SingleTaskExecutor`/
 * `DynamicTaskDispatcher`/`IosBackgroundTaskHandler` correctly is its own project).
 *
 * Precedence, most to least authoritative:
 * 1. Chain namespace ([fileStorage]'s chain definition store / chain queue +
 *    [isChainActive]) — checked first; if `id` happens to collide between the chain and
 *    standalone-task namespaces (nothing stops a caller from reusing the same string for
 *    both, since they're separate on-disk keyspaces), the chain wins.
 * 2. Standalone task namespace ([fileStorage]'s task metadata store, split by whether `id`
 *    has a dedicated Info.plist identifier or routes through the dynamic queue).
 * 3. [executionHistory] — the only durable signal once neither of the above finds anything
 *    live/queued, meaning whatever last happened to `id` is genuinely terminal (a pending
 *    retry always keeps metadata/chain-definition present — see the fields' own KDoc — so
 *    reaching this branch means there is nothing left to retry).
 *
 * @param isChainActive [ChainJobRegistry.isActive] — the one precise "is this chain running
 *   right now" signal available; everything else here is inferred, not observed.
 * @param isTaskPending A dedicated-identifier task's BGTaskScheduler pending-request check
 *   (mirrors `NativeTaskScheduler.isTaskPending`, threaded in rather than called directly so
 *   this function stays testable without a real `BGTaskScheduler`).
 * @param executionHistory Supplies recent [ExecutionRecord]s, newest first (mirrors
 *   `ExecutionHistoryStore.getRecords`) — threaded in for the same testability reason.
 */
internal suspend fun computeIosTaskState(
    id: String,
    fileStorage: IosFileStorage,
    permittedTaskIds: Set<String>,
    isChainActive: suspend (String) -> Boolean,
    isTaskPending: suspend (String) -> Boolean,
    executionHistory: suspend () -> List<ExecutionRecord>
): TaskState {
    // 1. Chain?
    val chainDefinitionExists = fileStorage.loadChainDefinition(id) != null
    val chainInQueue = id in fileStorage.getActiveChainIds()
    if (chainDefinitionExists || chainInQueue) {
        return when {
            isChainActive(id) -> TaskState.Running
            chainInQueue -> TaskState.Enqueued
            // Definition exists on disk but not currently queued and not registered as an
            // active job — a narrow window (e.g. between dequeue and job registration).
            // The chain has not reached a terminal state (its definition is still there),
            // so Enqueued is the honest answer, not Unknown.
            else -> TaskState.Enqueued
        }
    }

    // 2. Standalone task — dedicated Info.plist identifier, or dynamic queue?
    val meta = fileStorage.loadTaskMetadata(id, periodic = false)
        ?: fileStorage.loadTaskMetadata(id, periodic = true)
    if (meta != null) {
        return if (id in permittedTaskIds) {
            // Dedicated identifier: BGTaskScheduler's pending-requests list is the only
            // external signal — no OS API distinguishes "about to run" from "running now".
            if (isTaskPending(id)) TaskState.Enqueued else TaskState.Running
        } else {
            // Dynamic queue: dequeued-but-metadata-present is the closest available signal
            // to "likely executing" — see IosFileStorage.isTaskInDynamicQueue's KDoc.
            if (fileStorage.isTaskInDynamicQueue(id)) TaskState.Enqueued else TaskState.Running
        }
    }

    // 3. Nothing live or queued — the only remaining question is whether `id` ever existed
    // at all, and if so, how it ended. getRecords returns newest-first, so the first match
    // is the final outcome.
    val latest = executionHistory().firstOrNull { it.chainId == id } ?: return TaskState.Unknown
    return when (latest.status) {
        // ExecutionRecord carries no distinct "success message" field, only errorMessage
        // (documented as "null on success") — nothing meaningful to pass here.
        ExecutionStatus.SUCCESS -> TaskState.Succeeded()
        ExecutionStatus.FAILURE -> TaskState.Failed(latest.errorMessage)
        ExecutionStatus.ABANDONED -> TaskState.Failed(latest.errorMessage ?: "Retry budget exhausted")
        ExecutionStatus.TIMEOUT -> TaskState.Failed(latest.errorMessage ?: "Timed out")
        ExecutionStatus.SKIPPED -> TaskState.Cancelled
    }
}
