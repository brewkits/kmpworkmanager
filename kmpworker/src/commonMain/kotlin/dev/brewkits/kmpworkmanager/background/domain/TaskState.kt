package dev.brewkits.kmpworkmanager.background.domain

/**
 * Snapshot of a task or chain's lifecycle state, as observed via
 * [BackgroundTaskScheduler.observeTaskState].
 *
 * **Platform precision differs — read [BackgroundTaskScheduler.observeTaskState]'s KDoc
 * before relying on [Running] for anything more than a best-effort UI hint.** Android's
 * WorkManager tracks every state transition durably and precisely; iOS has no OS-level API
 * for "is this specific task executing right now" (`BGTaskScheduler` only exposes *future*
 * pending requests, never current execution), so [Running] there is inferred from persisted
 * signals rather than observed directly.
 */
sealed class TaskState {
    /** Scheduled and waiting for the OS to run it. Not yet started. */
    object Enqueued : TaskState()

    /**
     * Currently executing. On iOS this is a best-effort inference (see the class KDoc),
     * not a live, guaranteed-accurate signal the way it is on Android.
     */
    object Running : TaskState()

    /** Finished successfully. Terminal — no further transitions for this [id]. */
    data class Succeeded(val message: String? = null) : TaskState()

    /**
     * Finished with a failure that will not be retried further. Terminal — no further
     * transitions for this `id`. A failure that WILL still be retried is reported as
     * [Enqueued] (the task is queued again), not [Failed] — this only reflects a final,
     * no-more-attempts-left outcome.
     */
    data class Failed(val message: String? = null) : TaskState()

    /** Cancelled before or during execution. Terminal — no further transitions for this `id`. */
    object Cancelled : TaskState()

    /**
     * No record of this `id` at all: never scheduled under it, or its state has already
     * aged out of whatever retention the platform keeps (e.g. WorkManager's own history
     * pruning, or this library's own metadata/execution-history cleanup). Not itself an
     * error — an `id` that legitimately finished long ago and was cleaned up looks the same
     * as one that was never scheduled.
     */
    object Unknown : TaskState()

    /**
     * The subset of [TaskState] that identifies *which* state without the payload
     * ([Succeeded]/[Failed] carry an optional message) — what
     * [BackgroundTaskScheduler.queryTasks]'s `states` filter matches against, since
     * filtering by "give me the Failed ones" shouldn't require guessing or wildcarding an
     * error message.
     */
    enum class Kind { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
}

/** [TaskState.Kind] this [TaskState] belongs to. See [TaskState.Kind]'s KDoc for why this exists. */
val TaskState.kind: TaskState.Kind
    get() = when (this) {
        is TaskState.Enqueued -> TaskState.Kind.ENQUEUED
        is TaskState.Running -> TaskState.Kind.RUNNING
        is TaskState.Succeeded -> TaskState.Kind.SUCCEEDED
        is TaskState.Failed -> TaskState.Kind.FAILED
        is TaskState.Cancelled -> TaskState.Kind.CANCELLED
        is TaskState.Unknown -> TaskState.Kind.UNKNOWN
    }

/**
 * One [id] and its current [state], as returned by [BackgroundTaskScheduler.queryTasks].
 *
 * `id` is the same string passed to `enqueue`/`enqueueChain` (or the auto-generated chain id
 * if none was given) — the same identifier [BackgroundTaskScheduler.observeTaskState] takes.
 */
data class QueriedTask(val id: String, val state: TaskState)
