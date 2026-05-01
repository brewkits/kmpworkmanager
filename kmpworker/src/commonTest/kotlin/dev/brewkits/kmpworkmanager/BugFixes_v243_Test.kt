package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Common tests for v2.4.3 bug fixes and regressions related to periodic tasks.
 */
class BugFixes_v243_Test {

    /**
     * Verify that TaskTrigger.Periodic parameters are correctly validated.
     */
    @Test
    fun `Periodic trigger parameters are correctly assigned`() {
        val interval = 15L * 60 * 1000
        val flex = 10L * 60 * 1000
        
        val trigger = TaskTrigger.Periodic(
            intervalMs = interval,
            flexMs = flex,
            initialDelayMs = 0,
            runImmediately = true
        )
        
        assertEquals(interval, trigger.intervalMs)
        assertEquals(flex, trigger.flexMs)
        assertEquals(0L, trigger.initialDelayMs)
        assertTrue(trigger.runImmediately)
    }

    /**
     * Verify that runImmediately=false and initialDelayMs > 0 are mutually exclusive.
     */
    @Test
    fun `Periodic trigger throws if runImmediately is false and initialDelay is set`() {
        val interval = 15L * 60 * 1000
        
        val exception = runCatching {
            TaskTrigger.Periodic(
                intervalMs = interval,
                initialDelayMs = 5000L,
                runImmediately = false
            )
        }.exceptionOrNull()
        
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception.message?.contains("Ambiguous") == true)
    }

    /**
     * Verify that SecurityValidator.sanitizeHeaders removes headers with CR/LF.
     */
    @Test
    fun `sanitizeHeaders removes malicious entries with CR or LF`() {
        val input = mapOf(
            "Valid-Header" to "ValidValue",
            "Injected\nHeader" to "Value",
            "Header" to "Injected\rValue",
            "Another\r\nHeader" to "Value",
            "Safe" to "Value\nWith\rMixed"
        )

        val sanitized = dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator.sanitizeHeaders(input)

        assertEquals(1, sanitized?.size, "Only the valid header should remain")
        assertTrue(sanitized?.containsKey("Valid-Header") == true)
        assertEquals("ValidValue", sanitized?.get("Valid-Header"))
    }
}
