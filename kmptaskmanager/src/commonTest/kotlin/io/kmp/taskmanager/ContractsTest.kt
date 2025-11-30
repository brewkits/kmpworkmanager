package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTriggerTest {

    @Test
    fun `OneTime trigger with zero delay should be immediate`() {
        val trigger = TaskTrigger.OneTime()
        assertEquals(0L, trigger.initialDelayMs)
    }

    @Test
    fun `OneTime trigger with custom delay should preserve value`() {
        val delayMs = 5000L
        val trigger = TaskTrigger.OneTime(initialDelayMs = delayMs)
        assertEquals(delayMs, trigger.initialDelayMs)
    }

    @Test
    fun `Periodic trigger should preserve interval and flex`() {
        val intervalMs = 900_000L // 15 minutes
        val flexMs = 300_000L // 5 minutes
        val trigger = TaskTrigger.Periodic(intervalMs = intervalMs, flexMs = flexMs)

        assertEquals(intervalMs, trigger.intervalMs)
        assertEquals(flexMs, trigger.flexMs)
    }

    @Test
    fun `Periodic trigger without flex should have null flex`() {
        val trigger = TaskTrigger.Periodic(intervalMs = 900_000L)
        assertEquals(null, trigger.flexMs)
    }

    @Test
    fun `Exact trigger should preserve timestamp`() {
        val timestamp = 1704067200000L // Fixed timestamp
        val trigger = TaskTrigger.Exact(atEpochMillis = timestamp)
        assertEquals(timestamp, trigger.atEpochMillis)
    }

    @Test
    fun `Windowed trigger should preserve earliest and latest times`() {
        val earliest = 1704067200000L // Fixed timestamp
        val latest = earliest + 7200_000 // 2 hours later
        val trigger = TaskTrigger.Windowed(earliest = earliest, latest = latest)

        assertEquals(earliest, trigger.earliest)
        assertEquals(latest, trigger.latest)
    }

    @Test
    fun `ContentUri trigger should preserve URI and descendant flag`() {
        val uri = "content://media/external/images/media"
        val trigger = TaskTrigger.ContentUri(uriString = uri, triggerForDescendants = true)

        assertEquals(uri, trigger.uriString)
        assertTrue(trigger.triggerForDescendants)
    }

    @Test
    fun `ContentUri trigger without descendant flag should default to false`() {
        val trigger = TaskTrigger.ContentUri(uriString = "content://contacts")
        assertFalse(trigger.triggerForDescendants)
    }

    @Test
    fun `StorageLow trigger should be singleton`() {
        val trigger1 = TaskTrigger.StorageLow
        val trigger2 = TaskTrigger.StorageLow
        assertEquals(trigger1, trigger2)
    }

    @Test
    fun `BatteryLow trigger should be singleton`() {
        val trigger1 = TaskTrigger.BatteryLow
        val trigger2 = TaskTrigger.BatteryLow
        assertEquals(trigger1, trigger2)
    }

    @Test
    fun `BatteryOkay trigger should be singleton`() {
        val trigger1 = TaskTrigger.BatteryOkay
        val trigger2 = TaskTrigger.BatteryOkay
        assertEquals(trigger1, trigger2)
    }

    @Test
    fun `DeviceIdle trigger should be singleton`() {
        val trigger1 = TaskTrigger.DeviceIdle
        val trigger2 = TaskTrigger.DeviceIdle
        assertEquals(trigger1, trigger2)
    }
}

class ConstraintsTest {

    @Test
    fun `Default constraints should have all fields at default values`() {
        val constraints = Constraints()

        assertFalse(constraints.requiresNetwork)
        assertFalse(constraints.requiresUnmeteredNetwork)
        assertFalse(constraints.requiresCharging)
        assertFalse(constraints.allowWhileIdle)
        assertEquals(Qos.Background, constraints.qos)
        assertFalse(constraints.isHeavyTask)
        assertEquals(BackoffPolicy.EXPONENTIAL, constraints.backoffPolicy)
        assertEquals(30_000L, constraints.backoffDelayMs)
    }

    @Test
    fun `Constraints with network requirement should set flag`() {
        val constraints = Constraints(requiresNetwork = true)
        assertTrue(constraints.requiresNetwork)
    }

    @Test
    fun `Constraints with unmetered network should set flag`() {
        val constraints = Constraints(requiresUnmeteredNetwork = true)
        assertTrue(constraints.requiresUnmeteredNetwork)
    }

    @Test
    fun `Constraints with charging requirement should set flag`() {
        val constraints = Constraints(requiresCharging = true)
        assertTrue(constraints.requiresCharging)
    }

    @Test
    fun `Constraints with allowWhileIdle should set flag`() {
        val constraints = Constraints(allowWhileIdle = true)
        assertTrue(constraints.allowWhileIdle)
    }

    @Test
    fun `Constraints with heavy task flag should set flag`() {
        val constraints = Constraints(isHeavyTask = true)
        assertTrue(constraints.isHeavyTask)
    }

    @Test
    fun `Constraints with UserInitiated QoS should preserve value`() {
        val constraints = Constraints(qos = Qos.UserInitiated)
        assertEquals(Qos.UserInitiated, constraints.qos)
    }

    @Test
    fun `Constraints with Linear backoff policy should preserve value`() {
        val constraints = Constraints(backoffPolicy = BackoffPolicy.LINEAR)
        assertEquals(BackoffPolicy.LINEAR, constraints.backoffPolicy)
    }

    @Test
    fun `Constraints with custom backoff delay should preserve value`() {
        val customDelay = 60_000L
        val constraints = Constraints(backoffDelayMs = customDelay)
        assertEquals(customDelay, constraints.backoffDelayMs)
    }

    @Test
    fun `Constraints with multiple settings should preserve all values`() {
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
}

class BackoffPolicyTest {

    @Test
    fun `BackoffPolicy should have LINEAR and EXPONENTIAL values`() {
        val linear = BackoffPolicy.LINEAR
        val exponential = BackoffPolicy.EXPONENTIAL

        assertEquals("LINEAR", linear.name)
        assertEquals("EXPONENTIAL", exponential.name)
    }

    @Test
    fun `BackoffPolicy values should be different`() {
        val linear = BackoffPolicy.LINEAR
        val exponential = BackoffPolicy.EXPONENTIAL

        kotlin.test.assertNotEquals(linear, exponential)
    }
}

class QosTest {

    @Test
    fun `Qos should have all priority levels`() {
        val values = Qos.entries.toList()

        assertTrue(values.contains(Qos.Utility))
        assertTrue(values.contains(Qos.Background))
        assertTrue(values.contains(Qos.UserInitiated))
        assertTrue(values.contains(Qos.UserInteractive))
    }

    @Test
    fun `Qos Background should be available for default usage`() {
        val qos = Qos.Background
        assertEquals("Background", qos.name)
    }

    @Test
    fun `Qos UserInitiated should be higher priority than Background`() {
        val background = Qos.Background
        val userInitiated = Qos.UserInitiated

        // Higher priority QoS should have higher ordinal in enum
        assertTrue(userInitiated.ordinal > background.ordinal)
    }

    @Test
    fun `Qos UserInteractive should be highest priority`() {
        val userInteractive = Qos.UserInteractive
        val allQos = Qos.entries.toList()

        // UserInteractive should have the highest ordinal
        assertTrue(allQos.all { it.ordinal <= userInteractive.ordinal })
    }
}

class ExistingPolicyTest {

    @Test
    fun `ExistingPolicy should have KEEP and REPLACE values`() {
        val keep = ExistingPolicy.KEEP
        val replace = ExistingPolicy.REPLACE

        assertEquals("KEEP", keep.name)
        assertEquals("REPLACE", replace.name)
    }

    @Test
    fun `ExistingPolicy KEEP and REPLACE should be different`() {
        val keep = ExistingPolicy.KEEP
        val replace = ExistingPolicy.REPLACE

        kotlin.test.assertNotEquals(keep, replace)
    }
}

class ScheduleResultTest {

    @Test
    fun `ScheduleResult should have all result types`() {
        val values = ScheduleResult.entries.toList()

        assertTrue(values.contains(ScheduleResult.ACCEPTED))
        assertTrue(values.contains(ScheduleResult.REJECTED_OS_POLICY))
        assertTrue(values.contains(ScheduleResult.THROTTLED))
    }

    @Test
    fun `ScheduleResult ACCEPTED should indicate success`() {
        val result = ScheduleResult.ACCEPTED
        assertEquals("ACCEPTED", result.name)
    }

    @Test
    fun `ScheduleResult values should all be distinct`() {
        val accepted = ScheduleResult.ACCEPTED
        val rejected = ScheduleResult.REJECTED_OS_POLICY
        val throttled = ScheduleResult.THROTTLED

        kotlin.test.assertNotEquals(accepted, rejected)
        kotlin.test.assertNotEquals(accepted, throttled)
        kotlin.test.assertNotEquals(rejected, throttled)
    }
}
