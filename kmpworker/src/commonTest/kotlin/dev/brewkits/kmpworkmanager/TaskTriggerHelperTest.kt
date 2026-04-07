package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskTriggerHelperTest {

    @Test
    fun createTaskTriggerOneTime_with_zero_delay_should_return_OneTime_trigger_with_zero_delay() {
        val trigger = createTaskTriggerOneTime(0)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(0L, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun createTaskTriggerOneTime_with_positive_delay_should_return_OneTime_trigger_with_correct_delay() {
        val delayMs = 5000L
        val trigger = createTaskTriggerOneTime(delayMs)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(delayMs, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun createTaskTriggerOneTime_with_large_delay_should_handle_value_correctly() {
        val delayMs = Long.MAX_VALUE
        val trigger = createTaskTriggerOneTime(delayMs)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(delayMs, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun createConstraints_should_return_default_Constraints_instance() {
        val constraints = createConstraints()

        assertEquals(false, constraints.requiresNetwork)
        assertEquals(false, constraints.requiresUnmeteredNetwork)
        assertEquals(false, constraints.requiresCharging)
        assertEquals(false, constraints.allowWhileIdle)
        assertEquals(Qos.Background, constraints.qos)
        assertEquals(false, constraints.isHeavyTask)
        assertEquals(BackoffPolicy.EXPONENTIAL, constraints.backoffPolicy)
        assertEquals(30_000L, constraints.backoffDelayMs)
    }

    @Test
    fun createConstraints_should_return_new_instance_each_time() {
        val constraints1 = createConstraints()
        val constraints2 = createConstraints()

        // Should be equal but not the same instance
        assertEquals(constraints1, constraints2)
        assertTrue(constraints1 !== constraints2)
    }
}
