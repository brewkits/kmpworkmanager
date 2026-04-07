@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Backward compatibility tests for the current stable API surface.
 *
 * Note: Tests for deprecated triggers (StorageLow, BatteryLow, BatteryOkay, DeviceIdle)
 * were removed when those types were deleted from TaskTrigger
 * Use Constraints.systemConstraints with SystemConstraint instead.
 */
class BackwardCompatibilityTest {

    // ==================== SystemConstraint API ====================

    @Test
    fun SystemConstraint_API_should_work_correctly() {
        val constraints = Constraints(
            systemConstraints = setOf(
                SystemConstraint.ALLOW_LOW_BATTERY,
                SystemConstraint.DEVICE_IDLE
            )
        )

        assertEquals(2, constraints.systemConstraints.size)
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))
    }

    @Test
    fun Constraints_with_network_and_systemConstraints_should_work() {
        val constraints = Constraints(
            requiresNetwork = true,
            requiresCharging = true,
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE)
        )

        assertTrue(constraints.requiresNetwork)
        assertTrue(constraints.requiresCharging)
        assertEquals(1, constraints.systemConstraints.size)
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_STORAGE))
    }

    @Test
    fun Constraints_copy_should_work_with_systemConstraints_field() {
        val original = Constraints(
            requiresNetwork = true,
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
        )

        val copy = original.copy(
            systemConstraints = original.systemConstraints + SystemConstraint.DEVICE_IDLE
        )

        assertEquals(1, original.systemConstraints.size)
        assertEquals(2, copy.systemConstraints.size)
        assertTrue(copy.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
        assertTrue(copy.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))
        assertTrue(copy.requiresNetwork)
    }

    @Test
    fun Constraints_with_only_systemConstraints_should_have_correct_defaults() {
        val constraints = Constraints(
            systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
        )

        assertEquals(false, constraints.requiresNetwork)
        assertEquals(false, constraints.requiresCharging)
        assertEquals(false, constraints.isHeavyTask)
        assertEquals(1, constraints.systemConstraints.size)
    }

    // ==================== TaskTrigger Types ====================

    @Test
    fun OneTime_trigger_API() {
        val trigger1 = TaskTrigger.OneTime()
        assertEquals(0L, trigger1.initialDelayMs)

        val trigger2 = TaskTrigger.OneTime(initialDelayMs = 5000L)
        assertEquals(5000L, trigger2.initialDelayMs)
    }

    @Test
    fun Periodic_trigger_API() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000L)
        assertEquals(900_000L, trigger.intervalMs)
    }

    @Test
    fun Exact_trigger_API() {
        val timestamp = 1704067200000L
        val trigger = TaskTrigger.Exact(atEpochMillis = timestamp)
        assertEquals(timestamp, trigger.atEpochMillis)
    }

    @Test
    fun ContentUri_trigger_API() {
        val trigger = TaskTrigger.ContentUri(
            uriString = "content://media/external/images",
            triggerForDescendants = true
        )
        assertEquals("content://media/external/images", trigger.uriString)
        assertTrue(trigger.triggerForDescendants)
    }

    // ==================== Other Enums ====================

    @Test
    fun ExistingPolicy_values() {
        assertEquals("KEEP", ExistingPolicy.KEEP.name)
        assertEquals("REPLACE", ExistingPolicy.REPLACE.name)
    }

    @Test
    fun BackoffPolicy_values() {
        assertEquals("LINEAR", BackoffPolicy.LINEAR.name)
        assertEquals("EXPONENTIAL", BackoffPolicy.EXPONENTIAL.name)
    }

    @Test
    fun Qos_priority_levels() {
        assertNotNull(Qos.Utility)
        assertNotNull(Qos.Background)
        assertNotNull(Qos.UserInitiated)
        assertNotNull(Qos.UserInteractive)
    }
}
