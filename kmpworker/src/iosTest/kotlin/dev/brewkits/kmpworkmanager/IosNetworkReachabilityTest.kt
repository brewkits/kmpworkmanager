package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.IosNetworkReachability
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the reachability helper that replaced the `checkNetworkReachability() = true`
 * placeholder (issue #40).
 *
 * Note: in a test environment [IosNetworkReachability.isReachable] deliberately does NOT
 * start NWPathMonitor — its background update handler outlives the test process and made
 * the whole iOS suite flaky. So under test it always returns the conservative `true` seed.
 * The production NWPathMonitor path is exercised on-device, not here. These tests pin the
 * contract that the call is synchronous, non-throwing, and safe to call repeatedly.
 */
class IosNetworkReachabilityTest {

    @Test
    fun isReachable_isNonBlockingAndDoesNotThrow() {
        // Must return synchronously without throwing (cinterop wiring is the fragile part).
        assertTrue(IosNetworkReachability.isReachable(), "test-env snapshot is the `true` seed")
    }

    @Test
    fun isReachable_isStableAcrossRepeatedCalls() {
        repeat(50) {
            assertTrue(IosNetworkReachability.isReachable(), "repeated calls must stay stable and not throw")
        }
    }
}
