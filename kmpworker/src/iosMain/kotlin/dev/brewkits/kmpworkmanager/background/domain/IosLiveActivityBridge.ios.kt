package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * iOS actual implementation of [IosLiveActivityBridge].
 *
 * Subscribes to [TaskProgressBus] and delivers [WorkerProgress] updates
 * to Swift-registered callbacks on the **main thread** so the host can
 * safely call ActivityKit APIs.
 *
 * **Thread safety:** Each observer is stored in a `MutableMap` guarded by
 * a `@Volatile` flag. Concurrent registration/removal of observers is safe
 * because Swift's ActivityKit callback dispatch is inherently single-threaded
 * (always called from the main actor in the host app).
 *
 * **Memory:** Each observer registration creates one coroutine scope. Calling
 * [stopObserving] cancels that scope and removes the entry — no leaks.
 */
actual class IosLiveActivityBridge private constructor() {

    private val observers = mutableMapOf<String, CoroutineScope>()
    private val TAG = "IosLiveActivityBridge"

    /**
     * Starts delivering [WorkerProgress] events for [taskId] to [onProgress].
     * The [onProgress] callback is invoked on `Dispatchers.Main`.
     *
     * @param taskId Filter by task ID, or `null` to receive updates for all tasks.
     * @param onProgress Callback invoked for each progress event.
     */
    actual fun startObserving(taskId: String?, onProgress: (WorkerProgress) -> Unit) {
        val key = taskId ?: "*"
        // Cancel any existing observer for this key before re-registering.
        observers[key]?.cancel()

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        observers[key] = scope

        scope.launch {
            TaskProgressBus.events.collectLatest { event ->
                if (taskId == null || event.taskId == taskId) {
                    onProgress(event.progress)
                }
            }
        }
    }

    /**
     * Stops delivering progress updates for [taskId].
     *
     * @param taskId The task ID to unsubscribe, or `null` to remove the
     *   wildcard observer registered with `startObserving(null, …)`.
     */
    actual fun stopObserving(taskId: String?) {
        val key = taskId ?: "*"
        observers.remove(key)?.cancel()
    }

    actual companion object {
        actual val shared: IosLiveActivityBridge by lazy { IosLiveActivityBridge() }
    }
}
