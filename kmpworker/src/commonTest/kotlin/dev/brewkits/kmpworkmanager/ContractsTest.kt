@file:Suppress("DEPRECATION") // Deprecated trigger tests verify backward compatibility
@file:OptIn(dev.brewkits.kmpworkmanager.background.domain.AndroidOnly::class) // Tests explicitly cover Android-only APIs (ContentUri, SystemConstraint)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTriggerTest {

    @Test
    fun OneTime_trigger_with_zero_delay_should_be_immediate() {
        val trigger = TaskTrigger.OneTime()
        assertEquals(0L, trigger.initialDelayMs)
    }

    @Test
    fun OneTime_trigger_with_custom_delay_should_preserve_value() {
        val delayMs = 5000L
        val trigger = TaskTrigger.OneTime(initialDelayMs = delayMs)
        assertEquals(delayMs, trigger.initialDelayMs)
    }

    @Test
    fun Periodic_trigger_should_preserve_interval_and_flex() {
        val intervalMs = 900_000L // 15 minutes
        val flexMs = 300_000L // 5 minutes
        val trigger = TaskTrigger.Periodic(intervalMs = intervalMs, flexMs = flexMs)

        assertEquals(intervalMs, trigger.intervalMs)
        assertEquals(flexMs, trigger.flexMs)
    }

    @Test
    fun Periodic_trigger_without_flex_should_have_null_flex() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000L)
        assertEquals(null, trigger.flexMs)
    }

    @Test
    fun Exact_trigger_should_preserve_timestamp() {
        val timestamp = 1704067200000L // Fixed timestamp
        val trigger = TaskTrigger.Exact(atEpochMillis = timestamp)
        assertEquals(timestamp, trigger.atEpochMillis)
    }

    @Test
    fun Windowed_trigger_should_preserve_earliest_and_latest_times() {
        val earliest = 1704067200000L // Fixed timestamp
        val latest = earliest + 7200_000 // 2 hours later
        val trigger = TaskTrigger.Windowed(earliest = earliest, latest = latest)

        assertEquals(earliest, trigger.earliest)
        assertEquals(latest, trigger.latest)
    }

    @Test
    fun ContentUri_trigger_should_preserve_URI_and_descendant_flag() {
        val uri = "content://media/external/images/media"
        val trigger = TaskTrigger.ContentUri(uriString = uri, triggerForDescendants = true)

        assertEquals(uri, trigger.uriString)
        assertTrue(trigger.triggerForDescendants)
    }

    @Test
    fun ContentUri_trigger_without_descendant_flag_should_default_to_false() {
        val trigger = TaskTrigger.ContentUri(uriString = "content://contacts")
        assertFalse(trigger.triggerForDescendants)
    }

}

class SystemConstraintTest {

    @Test
    fun SystemConstraint_should_have_all_constraint_types() {
        val values = SystemConstraint.entries.toList()

        assertTrue(values.contains(SystemConstraint.ALLOW_LOW_STORAGE))
        assertTrue(values.contains(SystemConstraint.ALLOW_LOW_BATTERY))
        assertTrue(values.contains(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))
        assertTrue(values.contains(SystemConstraint.DEVICE_IDLE))
    }

    @Test
    fun SystemConstraint_ALLOW_LOW_STORAGE_should_be_available() {
        val constraint = SystemConstraint.ALLOW_LOW_STORAGE
        assertEquals("ALLOW_LOW_STORAGE", constraint.name)
    }

    @Test
    fun SystemConstraint_ALLOW_LOW_BATTERY_should_be_available() {
        val constraint = SystemConstraint.ALLOW_LOW_BATTERY
        assertEquals("ALLOW_LOW_BATTERY", constraint.name)
    }

    @Test
    fun SystemConstraint_REQUIRE_BATTERY_NOT_LOW_should_be_available() {
        val constraint = SystemConstraint.REQUIRE_BATTERY_NOT_LOW
        assertEquals("REQUIRE_BATTERY_NOT_LOW", constraint.name)
    }

    @Test
    fun SystemConstraint_DEVICE_IDLE_should_be_available() {
        val constraint = SystemConstraint.DEVICE_IDLE
        assertEquals("DEVICE_IDLE", constraint.name)
    }

    @Test
    fun SystemConstraint_values_should_all_be_distinct() {
        val allowStorage = SystemConstraint.ALLOW_LOW_STORAGE
        val allowBattery = SystemConstraint.ALLOW_LOW_BATTERY
        val requireBattery = SystemConstraint.REQUIRE_BATTERY_NOT_LOW
        val deviceIdle = SystemConstraint.DEVICE_IDLE

        kotlin.test.assertNotEquals(allowStorage, allowBattery)
        kotlin.test.assertNotEquals(allowStorage, requireBattery)
        kotlin.test.assertNotEquals(allowStorage, deviceIdle)
        kotlin.test.assertNotEquals(allowBattery, requireBattery)
        kotlin.test.assertNotEquals(allowBattery, deviceIdle)
        kotlin.test.assertNotEquals(requireBattery, deviceIdle)
    }
}

class ConstraintsTest {

    @Test
    fun Default_constraints_should_have_all_fields_at_default_values() {
        val constraints = Constraints()

        assertFalse(constraints.requiresNetwork)
        assertFalse(constraints.requiresUnmeteredNetwork)
        assertFalse(constraints.requiresCharging)
        assertFalse(constraints.allowWhileIdle)
        assertEquals(Qos.Background, constraints.qos)
        assertFalse(constraints.isHeavyTask)
        assertEquals(BackoffPolicy.EXPONENTIAL, constraints.backoffPolicy)
        assertEquals(30_000L, constraints.backoffDelayMs)
        assertTrue(constraints.systemConstraints.isEmpty())
    }

    @Test
    fun Constraints_with_network_requirement_should_set_flag() {
        val constraints = Constraints(requiresNetwork = true)
        assertTrue(constraints.requiresNetwork)
    }

    @Test
    fun Constraints_with_unmetered_network_should_set_flag() {
        val constraints = Constraints(requiresUnmeteredNetwork = true)
        assertTrue(constraints.requiresUnmeteredNetwork)
    }

    @Test
    fun Constraints_with_charging_requirement_should_set_flag() {
        val constraints = Constraints(requiresCharging = true)
        assertTrue(constraints.requiresCharging)
    }

    @Test
    fun Constraints_with_allowWhileIdle_should_set_flag() {
        val constraints = Constraints(allowWhileIdle = true)
        assertTrue(constraints.allowWhileIdle)
    }

    @Test
    fun Constraints_with_heavy_task_flag_should_set_flag() {
        val constraints = Constraints(isHeavyTask = true)
        assertTrue(constraints.isHeavyTask)
    }

    @Test
    fun Constraints_with_UserInitiated_QoS_should_preserve_value() {
        val constraints = Constraints(qos = Qos.UserInitiated)
        assertEquals(Qos.UserInitiated, constraints.qos)
    }

    @Test
    fun Constraints_with_Linear_backoff_policy_should_preserve_value() {
        val constraints = Constraints(backoffPolicy = BackoffPolicy.LINEAR)
        assertEquals(BackoffPolicy.LINEAR, constraints.backoffPolicy)
    }

    @Test
    fun Constraints_with_custom_backoff_delay_should_preserve_value() {
        val customDelay = 60_000L
        val constraints = Constraints(backoffDelayMs = customDelay)
        assertEquals(customDelay, constraints.backoffDelayMs)
    }

    @Test
    fun Constraints_with_multiple_settings_should_preserve_all_values() {
        val constraints = Constraints(
            requiresNetwork = true,
            requiresCharging = true,
            isHeavyTask = true,
            qos = Qos.Utility,
            backoffPolicy = BackoffPolicy.LINEAR,
            backoffDelayMs = 45_000L
        )

        assertTrue(constraints.requiresNetwork)
        assertTrue(constraints.requiresCharging)
        assertTrue(constraints.isHeavyTask)
        assertEquals(Qos.Utility, constraints.qos)
        assertEquals(BackoffPolicy.LINEAR, constraints.backoffPolicy)
        assertEquals(45_000L, constraints.backoffDelayMs)
    }

    @Test
    fun Constraints_with_single_system_constraint_should_preserve_value() {
        val constraints = Constraints(
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_BATTERY)
        )

        assertEquals(1, constraints.systemConstraints.size)
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
    }

    @Test
    fun Constraints_with_multiple_system_constraints_should_preserve_all_values() {
        val constraints = Constraints(
            systemConstraints = setOf(
                SystemConstraint.ALLOW_LOW_BATTERY,
                SystemConstraint.DEVICE_IDLE,
                SystemConstraint.REQUIRE_BATTERY_NOT_LOW
            )
        )

        assertEquals(3, constraints.systemConstraints.size)
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.ALLOW_LOW_BATTERY))
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))
        assertTrue(constraints.systemConstraints.contains(SystemConstraint.REQUIRE_BATTERY_NOT_LOW))
    }

    @Test
    fun Constraints_with_empty_system_constraints_should_have_empty_set() {
        val constraints = Constraints(
            systemConstraints = emptySet()
        )

        assertTrue(constraints.systemConstraints.isEmpty())
    }

    @Test
    fun Constraints_copy_with_system_constraints_should_preserve_values() {
        val original = Constraints(
            requiresNetwork = true,
            systemConstraints = setOf(SystemConstraint.ALLOW_LOW_STORAGE)
        )

        val copy = original.copy(
            systemConstraints = original.systemConstraints + SystemConstraint.DEVICE_IDLE
        )

        assertEquals(2, copy.systemConstraints.size)
        assertTrue(copy.systemConstraints.contains(SystemConstraint.ALLOW_LOW_STORAGE))
        assertTrue(copy.systemConstraints.contains(SystemConstraint.DEVICE_IDLE))
        assertTrue(copy.requiresNetwork)
    }
}

class BackoffPolicyTest {

    @Test
    fun BackoffPolicy_should_have_LINEAR_and_EXPONENTIAL_values() {
        val linear = BackoffPolicy.LINEAR
        val exponential = BackoffPolicy.EXPONENTIAL

        assertEquals("LINEAR", linear.name)
        assertEquals("EXPONENTIAL", exponential.name)
    }

    @Test
    fun BackoffPolicy_values_should_be_different() {
        val linear = BackoffPolicy.LINEAR
        val exponential = BackoffPolicy.EXPONENTIAL

        kotlin.test.assertNotEquals(linear, exponential)
    }
}

class QosTest {

    @Test
    fun Qos_should_have_all_priority_levels() {
        val values = Qos.entries.toList()

        assertTrue(values.contains(Qos.Utility))
        assertTrue(values.contains(Qos.Background))
        assertTrue(values.contains(Qos.UserInitiated))
        assertTrue(values.contains(Qos.UserInteractive))
    }

    @Test
    fun Qos_Background_should_be_available_for_default_usage() {
        val qos = Qos.Background
        assertEquals("Background", qos.name)
    }

    @Test
    fun Qos_UserInitiated_should_be_higher_priority_than_Background() {
        val background = Qos.Background
        val userInitiated = Qos.UserInitiated

        // Higher priority QoS should have higher ordinal in enum
        assertTrue(userInitiated.ordinal > background.ordinal)
    }

    @Test
    fun Qos_UserInteractive_should_be_highest_priority() {
        val userInteractive = Qos.UserInteractive
        val allQos = Qos.entries.toList()

        // UserInteractive should have the highest ordinal
        assertTrue(allQos.all { it.ordinal <= userInteractive.ordinal })
    }
}

class ExistingPolicyEnumTest {

    @Test
    fun ExistingPolicy_should_have_KEEP_and_REPLACE_values() {
        val keep = ExistingPolicy.KEEP
        val replace = ExistingPolicy.REPLACE

        assertEquals("KEEP", keep.name)
        assertEquals("REPLACE", replace.name)
    }

    @Test
    fun ExistingPolicy_KEEP_and_REPLACE_should_be_different() {
        val keep = ExistingPolicy.KEEP
        val replace = ExistingPolicy.REPLACE

        kotlin.test.assertNotEquals(keep, replace)
    }
}

class ScheduleResultTest {

    @Test
    fun ScheduleResult_should_have_all_result_types() {
        val values = ScheduleResult.entries.toList()

        assertTrue(values.contains(ScheduleResult.ACCEPTED))
        assertTrue(values.contains(ScheduleResult.REJECTED_OS_POLICY))
        assertTrue(values.contains(ScheduleResult.THROTTLED))
    }

    @Test
    fun ScheduleResult_ACCEPTED_should_indicate_success() {
        val result = ScheduleResult.ACCEPTED
        assertEquals("ACCEPTED", result.name)
    }

    @Test
    fun ScheduleResult_values_should_all_be_distinct() {
        val accepted = ScheduleResult.ACCEPTED
        val rejected = ScheduleResult.REJECTED_OS_POLICY
        val throttled = ScheduleResult.THROTTLED

        kotlin.test.assertNotEquals(accepted, rejected)
        kotlin.test.assertNotEquals(accepted, throttled)
        kotlin.test.assertNotEquals(rejected, throttled)
    }
}
