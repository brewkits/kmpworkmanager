package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.StoredEvent
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the cross-platform (commonMain) fixes shipped in v3.5.0.
 *
 * Per the project convention (CLAUDE.md — "Testing Conventions") this file belongs to one
 * release and is never edited afterwards.
 */
class V350BugFixesTest {

    // ── #5 — a trailing root label walked straight through the SSRF blocklist ──────────
    //
    // `stripPort` returned `hostname.lowercase()` with no `trimEnd('.')`, so "localhost."
    // — a perfectly valid FQDN that resolves to exactly the same host as "localhost" —
    // matched no entry in `isBlockedHostname` and was allowed.
    //
    // The numeric cases below are NOT regression witnesses: stashing the fix leaves them
    // green, because "127.0.0.1." splits into five parts and was already rejected by the
    // `!isValidIPv4` arm. They are here as invariants — the shape of the fix (canonicalise
    // in `stripPort`) makes it easy for a later change to move the trailing-dot handling
    // somewhere that covers only named hosts.

    @Test
    fun trailingDotFqdn_doesNotBypassLocalhostBlock() {
        assertFalse(SecurityValidator.validateURL("http://localhost./admin"))
        assertFalse(SecurityValidator.validateURL("http://localhost.:8080/admin"))
        // Multiple root labels are still the same host.
        assertFalse(SecurityValidator.validateURL("http://localhost../admin"))
    }

    @Test
    fun trailingDotFqdn_doesNotBypassPrivateIpBlock() {
        assertFalse(SecurityValidator.validateURL("http://127.0.0.1./x"))
        assertFalse(SecurityValidator.validateURL("http://10.0.0.1./x"))
        assertFalse(SecurityValidator.validateURL("http://192.168.1.1.:9000/x"))
    }

    @Test
    fun trailingDotFqdn_doesNotBypassCloudMetadataBlock() {
        assertFalse(SecurityValidator.validateURL("http://metadata.google.internal./computeMetadata/v1/"))
        assertFalse(SecurityValidator.validateURL("http://169.254.169.254./latest/meta-data/"))
    }

    /**
     * The multi-`@` authority path calls `stripPort` on every candidate segment, so the one
     * fix has to cover it too — otherwise "a@b@localhost." reopens the same hole one level
     * further in.
     */
    @Test
    fun trailingDotFqdn_doesNotBypassBlockViaUserInfo() {
        assertFalse(SecurityValidator.validateURL("http://user:pass@localhost./x"))
        assertFalse(SecurityValidator.validateURL("http://evil.com@127.0.0.1./x"))
        assertFalse(SecurityValidator.validateURL("http://a@b@localhost./x"))
    }

    /**
     * Positive control: a trailing dot on a *legitimate* host is valid DNS syntax and must
     * still be accepted. A fix that blanket-rejected the dot would pass every assertion
     * above while breaking real callers.
     */
    @Test
    fun trailingDotFqdn_onPublicHost_isStillAllowed() {
        assertTrue(SecurityValidator.validateURL("https://example.com./resource"))
    }

    /** A hostname of nothing but root labels is not a host — it must not be accepted. */
    @Test
    fun authorityOfOnlyDots_isRejected() {
        assertFalse(SecurityValidator.validateURL("http://./x"))
        assertFalse(SecurityValidator.validateURL("http://../x"))
    }

    // ── #9 — a save that wrote nothing reported success with a valid-looking id ────────
    //
    // The disk-space guard in both AndroidEventStore and IosEventStore returned the
    // freshly minted UUID after skipping the write, so TaskEventManager logged
    // "Saved event <id>" for an event that was never persisted and could never be looked
    // up again. The stores now throw; this pins the contract TaskEventManager.emit
    // documents ("Event ID if saved successfully, null otherwise") and — just as
    // important — that live UI still receives the event.

    @AfterTest
    fun tearDown() {
        TaskEventManager.resetForTest()
    }

    private class ThrowingEventStore : EventStore {
        override suspend fun saveEvent(event: TaskCompletionEvent): String =
            throw IllegalStateException("disk critically low — event not persisted")

        override suspend fun getUnconsumedEvents(): List<StoredEvent> = emptyList()
        override suspend fun markEventConsumed(eventId: String) = Unit
        override suspend fun clearOldEvents(olderThanMs: Long): Int = 0
        override suspend fun clearAll() = Unit
        override suspend fun getEventCount(): Int = 0
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun emit_whenStoreCannotPersist_returnsNull_andStillReachesTheBus() =
        runTest(UnconfinedTestDispatcher()) {
            // TaskEventBus is a global SharedFlow with a replay buffer, so the event has to
            // carry a name unique to this test (CLAUDE.md — "Testing Conventions").
            val taskName = "v350-unpersistable-event-${kotlin.random.Random.nextInt()}"
            val seen = mutableListOf<TaskCompletionEvent>()
            val collector = launch {
                TaskEventBus.events.filter { it.taskName == taskName }.collect { seen.add(it) }
            }

            TaskEventManager.resetForTest()
            TaskEventManager.initialize(ThrowingEventStore())

            val eventId = TaskEventManager.emit(
                TaskCompletionEvent(taskName = taskName, success = true, message = "done")
            )

            assertNull(eventId, "a store that persisted nothing must not hand back an event id")
            assertEquals(1, seen.size, "persistence failure must not suppress the live event")
            assertTrue(seen.single().success)

            collector.cancel()
        }
}
