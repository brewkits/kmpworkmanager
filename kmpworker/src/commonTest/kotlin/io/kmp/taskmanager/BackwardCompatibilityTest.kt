package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Critical tests for v3.0.0 backward compatibility.
 *
 * These tests ensure that code written for v2.x continues to work in v3.0.0
 * without modifications (deprecated APIs still functional).
 */
class BackwardCompatibilityTest {

    // ==================== Deprecated Triggers Still Work ====================

    @Test
    fun `deprecated BatteryLow trigger should still be accessible`() {
        // v2.x code: TaskTrigger.BatteryLow
        val trigger = TaskTrigger.BatteryLow

        assertNotNull(trigger, "BatteryLow trigger should still exist")
        assertTrue(trigger is TaskTrigger, "Should be a valid TaskTrigger")
    }

    @Test
    fun `deprecated BatteryOkay trigger should still be accessible`() {
        val trigger = TaskTrigger.BatteryOkay

        assertNotNull(trigger, "BatteryOkay trigger should still exist")
        assertTrue(trigger is TaskTrigger, "Should be a valid TaskTrigger")
    }

    @Test
    fun `deprecated StorageLow trigger should still be accessible`() {
        val trigger = TaskTrigger.StorageLow

        assertNotNull(trigger, "StorageLow trigger should still exist")
        assertTrue(trigger is TaskTrigger, "Should be a valid TaskTrigger")
    }

    @Test
    fun `deprecated DeviceIdle trigger should still be accessible`() {
        val trigger = TaskTrigger.DeviceIdle

        assertNotNull(trigger, "DeviceIdle trigger should still exist")
        assertTrue(trigger is TaskTrigger, "Should be a valid TaskTrigger")
    }

    // ==================== Old API Patterns Work ====================

    @Test
    fun `v2 style task scheduling syntax should compile`() {
        // This test verifies that v2.x code patterns still work

        // Pattern 1: Using deprecated triggers
        val trigger1 = TaskTrigger.BatteryLow
        assertTrue(trigger1 is TaskTrigger)

        // Pattern 2: Using old constraints (no systemConstraints)
        val constraints1 = Constraints(
            requiresNetwork = true,
            requiresCharging = true
        )
        assertEquals(true, constraints1.requiresNetwork)
        assertEquals(true, constraints1.requiresCharging)

        // Pattern 3: TaskRequest without new parameters
        val request = TaskRequest(
            workerClassName = "MyWorker",
            inputJson = """{"key":"value"}"""
        )
        assertEquals("MyWorker", request.workerClassName)
    }

    // ==================== New API Works Alongside Old ====================

    @Test
    fun `new SystemConstraint API should work correctly`() {
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
    fun `mixing old and new API should work`() {
        // v3.0 style: Use SystemConstraints alongside old parameters
        val constraints = Constraints(
            requiresNetwork = true,  // Old API
            requiresCharging = true,  // Old API
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE)  // New API
        )

        // Old parameters still work
        assertTrue(constraints.requiresNetwork)
        assertTrue(constraints.requiresCharging)

        // New parameters also work
        assertEquals(1, constraints.systemConstraints.size)
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_STORAGE))
    }

    // ==================== Constraints Data Class Compatibility ====================

    @Test
    fun `Constraints copy should work with new systemConstraints field`() {
        val original = Constraints(
            requiresNetwork = true,
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
        )

        val copy = original.copy(
            systemConstraints = original.systemConstraints + SystemConstraint.DEVICE_IDLE
        )

        // Original unchanged
        assertEquals(1, original.systemConstraints.size)

        // Copy has updated systemConstraints
        assertEquals(2, copy.systemConstraints.size)
        assertTrue(copy.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
        assertTrue(copy.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))

        // Other fields preserved
        assertTrue(copy.requiresNetwork)
    }

    @Test
    fun `Constraints with only systemConstraints should have correct defaults`() {
        val constraints = Constraints(
            systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
        )

        // Default values preserved
        assertEquals(false, constraints.requiresNetwork)
        assertEquals(false, constraints.requiresCharging)
        assertEquals(false, constraints.isHeavyTask)

        // systemConstraints set correctly
        assertEquals(1, constraints.systemConstraints.size)
    }

    // ==================== TaskTrigger Types Unchanged ====================

    @Test
    fun `OneTime trigger API unchanged from v2`() {
        val trigger1 = TaskTrigger.OneTime()
        assertEquals(0L, trigger1.initialDelayMs)

        val trigger2 = TaskTrigger.OneTime(initialDelayMs = 5000L)
        assertEquals(5000L, trigger2.initialDelayMs)
    }

    @Test
    fun `Periodic trigger API unchanged from v2`() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000L)
        assertEquals(900_000L, trigger.intervalMs)
    }

    @Test
    fun `Exact trigger API unchanged from v2`() {
        val timestamp = 1704067200000L // Fixed timestamp for testing
        val trigger = TaskTrigger.Exact(atEpochMillis = timestamp)
        assertEquals(timestamp, trigger.atEpochMillis)
    }

    @Test
    fun `ContentUri trigger API unchanged from v2`() {
        val trigger = TaskTrigger.ContentUri(
            uriString = "content://media/external/images",
            triggerForDescendants = true
        )
        assertEquals("content://media/external/images", trigger.uriString)
        assertTrue(trigger.triggerForDescendants)
    }

    // ==================== ExistingPolicy Unchanged ====================

    @Test
    fun `ExistingPolicy values unchanged from v2`() {
        val keep = ExistingPolicy.KEEP
        val replace = ExistingPolicy.REPLACE

        assertEquals("KEEP", keep.name)
        assertEquals("REPLACE", replace.name)
    }

    // ==================== BackoffPolicy Unchanged ====================

    @Test
    fun `BackoffPolicy values unchanged from v2`() {
        val linear = BackoffPolicy.LINEAR
        val exponential = BackoffPolicy.EXPONENTIAL

        assertEquals("LINEAR", linear.name)
        assertEquals("EXPONENTIAL", exponential.name)
    }

    // ==================== Qos Enum Unchanged ====================

    @Test
    fun `Qos priority levels unchanged from v2`() {
        val utility = Qos.Utility
        val background = Qos.Background
        val userInitiated = Qos.UserInitiated
        val userInteractive = Qos.UserInteractive

        // All values exist
        assertNotNull(utility)
        assertNotNull(background)
        assertNotNull(userInitiated)
        assertNotNull(userInteractive)
    }
}

/**
 * Tests for v3.0.0 migration guide examples.
 *
 * These tests verify that code examples in migration documentation actually work.
 */
class MigrationExamplesTest {

    @Test
    fun `migration example 1 - replace BatteryLow with SystemConstraint`() {
        // OLD (v2.x) - deprecated but still works
        val oldTrigger = TaskTrigger.BatteryLow
        assertNotNull(oldTrigger)

        // NEW (v3.0+) - recommended approach
        val newConstraints = Constraints(
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
        )

        assertTrue(newConstraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
    }

    @Test
    fun `migration example 2 - replace StorageLow with SystemConstraint`() {
        // OLD (v2.x)
        val oldTrigger = TaskTrigger.StorageLow
        assertNotNull(oldTrigger)

        // NEW (v3.0+)
        val newConstraints = Constraints(
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE)
        )

        assertTrue(newConstraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_STORAGE))
    }

    @Test
    fun `migration example 3 - replace BatteryOkay with SystemConstraint`() {
        // OLD (v2.x)
        val oldTrigger = TaskTrigger.BatteryOkay
        assertNotNull(oldTrigger)

        // NEW (v3.0+)
        val newConstraints = Constraints(
            systemConstraints = setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
        )

        assertTrue(newConstraints.systemConstraints.contains(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))
    }

    @Test
    fun `migration example 4 - replace DeviceIdle with SystemConstraint`() {
        // OLD (v2.x)
        val oldTrigger = TaskTrigger.DeviceIdle
        assertNotNull(oldTrigger)

        // NEW (v3.0+)
        val newConstraints = Constraints(
            systemConstraints = setOf(SystemConstraint.DEVICE_IDLE)
        )

        assertTrue(newConstraints.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))
    }

    @Test
    fun `migration example 5 - multiple SystemConstraints`() {
        // NEW (v3.0+) - combine multiple system constraints
        val constraints = Constraints(
            requiresCharging = true,
            systemConstraints = setOf(
                SystemConstraint.REQUIRE_BATTERY_NOT_LOW,
                SystemConstraint.DEVICE_IDLE
            )
        )

        assertEquals(2, constraints.systemConstraints.size)
        assertTrue(constraints.requiresCharging)
    }
}
