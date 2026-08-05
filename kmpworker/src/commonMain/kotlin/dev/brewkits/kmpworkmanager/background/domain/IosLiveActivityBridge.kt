package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * iOS Live Activity & Dynamic Island Progress Bridge.
 *
 * Provides a lightweight KMP-friendly API for iOS host applications to relay
 * background worker progress to iOS Live Activities (ActivityKit) and Dynamic Island.
 *
 * **Usage from Swift:**
 * ```swift
 * // In your AppDelegate or SceneDelegate:
 * let bridge = IosLiveActivityBridge.shared
 * bridge.startObserving { progress in
 *     Task {
 *         guard let activity = self.currentActivity else { return }
 *         let contentState = UploadAttributes.ContentState(
 *             progress: Double(progress.percentage) / 100.0,
 *             message: progress.message ?? ""
 *         )
 *         await activity.update(using: contentState)
 *     }
 * }
 * ```
 *
 * **Pattern:** This bridge subscribes to [TaskProgressBus] (shared, singleton) and
 * emits each [WorkerProgress] update via [onProgress] callback to the Swift host.
 * Swift owns the ActivityKit types — no ActivityKit symbols leak into Kotlin.
 *
 * **Thread safety:** [onProgress] is always invoked on the main thread via
 * [kotlinx.coroutines.Dispatchers.Main].
 *
 * See `docs/IOS_LIVE_ACTIVITIES.md` for full integration guide.
 */
expect class IosLiveActivityBridge {
    /**
     * Registers a callback to receive [WorkerProgress] updates for the given [taskId].
     * Pass `null` for [taskId] to receive updates from **all** running workers.
     *
     * The [onProgress] lambda is invoked on the main dispatcher.
     * Call [stopObserving] with the same [taskId] when the Live Activity ends.
     */
    fun startObserving(taskId: String?, onProgress: (WorkerProgress) -> Unit)

    /**
     * Stops delivering progress updates for the given [taskId].
     * Pass `null` to stop all observers registered with `null` taskId.
     */
    fun stopObserving(taskId: String?)

    companion object {
        /** Shared singleton — use this from Swift. */
        val shared: IosLiveActivityBridge
    }
}
