package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskTriggerHelperTest {

    @Test
    fun `createTaskTriggerOneTime with zero delay should return OneTime trigger with zero delay`() {
        val trigger = createTaskTriggerOneTime(0)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(0L, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun `createTaskTriggerOneTime with positive delay should return OneTime trigger with correct delay`() {
        val delayMs = 5000L
        val trigger = createTaskTriggerOneTime(delayMs)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(delayMs, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun `createTaskTriggerOneTime with large delay should handle value correctly`() {
        val delayMs = Long.MAX_VALUE
        val trigger = createTaskTriggerOneTime(delayMs)

        assertTrue(trigger is TaskTrigger.OneTime)
        assertEquals(delayMs, (trigger as TaskTrigger.OneTime).initialDelayMs)
    }

    @Test
    fun `createConstraints should return default Constraints instance`() {
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
    fun `createConstraints should return new instance each time`() {
        val constraints1 = createConstraints()
        val constraints2 = createConstraints()

        // Should be equal but not the same instance
        assertEquals(constraints1, constraints2)
        assertTrue(constraints1 !== constraints2)
    }
}
