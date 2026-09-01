package dev.brewkits.kmpworkmanager.background.domain

/**
 * Android stub for [IosLiveActivityBridge].
 * Live Activities (ActivityKit) are an iOS-only API — this class is a no-op on Android.
 * It exists only to satisfy the `expect/actual` contract so common code can reference
 * [IosLiveActivityBridge] without platform guards.
 */
actual class IosLiveActivityBridge private constructor() {

    actual fun startObserving(taskId: String?, onProgress: (WorkerProgress) -> Unit) {
        // No-op on Android — Live Activities are iOS only.
    }

    actual fun stopObserving(taskId: String?) {
        // No-op on Android.
    }

    actual companion object {
        actual val shared: IosLiveActivityBridge by lazy { IosLiveActivityBridge() }
    }
}
