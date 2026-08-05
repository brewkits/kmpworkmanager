package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IosLiveActivityBridgeTest {

    @Test
    fun sharedSingletonInstance_isNotNull() {
        val instance = IosLiveActivityBridge.shared
        assertNotNull(instance, "Shared instance must not be null")
    }

    @Test
    fun startAndStopObserving_doesNotThrow() = runTest {
        val bridge = IosLiveActivityBridge.shared
        var receivedCount = 0

        bridge.startObserving("task-123") {
            receivedCount++
        }

        bridge.stopObserving("task-123")
    }

    @Test
    fun wildcardObserving_doesNotThrow() = runTest {
        val bridge = IosLiveActivityBridge.shared

        bridge.startObserving(null) { _ -> }
        bridge.stopObserving(null)
    }
}
