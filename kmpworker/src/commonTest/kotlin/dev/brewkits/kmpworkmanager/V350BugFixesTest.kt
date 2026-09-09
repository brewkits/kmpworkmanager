package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.StoredEvent
import dev.brewkits.kmpworkmanager.utils.BackoffJitter
import dev.brewkits.kmpworkmanager.utils.CustomLogger
import dev.brewkits.kmpworkmanager.utils.Logger
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
import kotlin.random.Random

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

    // ── Backoff was deterministic on both platforms ───────────────────────────────────
    //
    // iOS multiplied a fixed base by the attempt number; Android handed the same fixed base
    // to WorkRequest.setBackoffCriteria, whose own math is equally deterministic. An outage
    // that failed 100 000 installs in the same second produced 100 000 retries in the same
    // second, and again in lockstep at every later attempt — the retry policy turning one
    // outage into a load test on the customer's recovering backend.

    @Test
    fun jitter_staysWithinTheEqualJitterWindow() {
        val delay = 30_000L
        repeat(500) {
            val jittered = BackoffJitter.apply(delay)
            assertTrue(
                jittered in (delay / 2)..delay,
                "equal jitter must land in [${delay / 2}, $delay], got $jittered",
            )
        }
    }

    @Test
    fun jitter_neverLengthensTheDelay() {
        // Load-bearing: both callers apply an upper cap (1 h on iOS, WorkManager's own on
        // Android) BEFORE jitter. If jitter could grow the value, those caps would leak.
        listOf(1L, 999L, 30_000L, 60 * 60 * 1000L).forEach { delay ->
            repeat(200) {
                assertTrue(BackoffJitter.apply(delay) <= delay, "jitter must never exceed $delay")
            }
        }
    }

    @Test
    fun jitter_actuallySpreadsAcrossCallers() {
        // The whole point. A "jitter" that returns one value would satisfy the bounds checks
        // above and still leave every device retrying in the same instant.
        val distinct = (1..200).map { BackoffJitter.apply(30_000L) }.toSet()
        assertTrue(distinct.size > 10, "expected a spread of delays, got ${distinct.size} distinct values")
    }

    @Test
    fun jitter_leavesAZeroDelayAlone() {
        // A caller asking for no delay is not asking for a random one.
        assertEquals(0L, BackoffJitter.apply(0L))
        assertEquals(-5L, BackoffJitter.apply(-5L))
    }

    @Test
    fun jitter_isDeterministicUnderAnInjectedSeed() {
        assertEquals(BackoffJitter.apply(30_000L, Random(42)), BackoffJitter.apply(30_000L, Random(42)))
    }

    // ── sanitizedURL redacted the query string but not the credentials ────────────────
    //
    // Its output is not only logged: it is embedded in WorkerResult messages, which become
    // TaskCompletionEvents and are written to the event store. A URL carrying HTTP Basic
    // credentials in its authority — still normal for internal services — therefore had its
    // password persisted to disk.

    @Test
    fun sanitizedUrl_redactsUserInfoCredentials() {
        assertEquals(
            "https://[REDACTED]@internal.example.com/reports",
            SecurityValidator.sanitizedURL("https://svc-account:hunter2@internal.example.com/reports"),
        )
    }

    @Test
    fun sanitizedUrl_redactsCredentialsAndQueryTogether() {
        val sanitized = SecurityValidator.sanitizedURL("https://u:p@api.example.com/v1/data?token=secret")
        assertFalse(sanitized.contains("p@"), "password must not survive: $sanitized")
        assertFalse(sanitized.contains("secret"), "query must still be redacted: $sanitized")
        assertEquals("https://[REDACTED]@api.example.com/v1/data?[REDACTED]", sanitized)
    }

    @Test
    fun sanitizedUrl_leavesACredentialFreeUrlIntact() {
        // An '@' later in the path must not be mistaken for UserInfo.
        assertEquals(
            "https://api.example.com/users/a@b.com",
            SecurityValidator.sanitizedURL("https://api.example.com/users/a@b.com"),
        )
    }

    /**
     * `SecureRedirectFollowing` calls `sanitizedURL` on a URL that has just FAILED validation,
     * to build the "Redirect to unsafe URL blocked" message. That input is attacker-influenced
     * and need not be well formed, so the redaction must not be able to throw while an error
     * is being reported — an exception there would replace a clean security block with a crash
     * inside the very branch that exists to stop the request.
     */
    @Test
    fun sanitizedUrl_doesNotThrowOnMalformedInput() {
        listOf(
            "https://",
            "http://",
            "https://@",
            "https://@@",
            "http://./x",
            "://no-scheme",
            "",
            "not-a-url-at-all",
            "https://user@",
            "https://user:pass@?q=1",
        ).forEach { input ->
            // The assertion is that this returns at all.
            val sanitized = SecurityValidator.sanitizedURL(input)
            assertFalse(sanitized.contains("pass"), "credentials must not survive in '$sanitized'")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Phase 6 — the no-op cancellation defaults were silent
    // ---------------------------------------------------------------------------------------

    /**
     * A scheduler that inherits every default. Represents a third-party or test implementation
     * written before cancelByTag/cancelByWorkerClass existed.
     */
    private open class DefaultsOnlyScheduler : BackgroundTaskScheduler {
        override suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy,
            tags: Set<String>,
            deadlineMs: Long?
        ): ScheduleResult = ScheduleResult.ACCEPTED

        override fun cancel(id: String) = Unit
        override fun cancelAll() = Unit
        override fun beginWith(task: TaskRequest): TaskChain = error("not needed for this test")
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = error("not needed for this test")
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) = Unit
        override fun flushPendingProgress() = Unit
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() = Unit
    }

    /**
     * A second, distinct implementation — used to prove the warning is not global-once.
     * A subclass rather than a `by` delegate on purpose: delegation would forward the call to
     * the inner instance, so the warning would be attributed to *its* class and this test would
     * pass for the wrong reason.
     */
    private class OtherDefaultsOnlyScheduler : DefaultsOnlyScheduler()

    private fun captureWarnings(block: () -> Unit): List<String> {
        val captured = mutableListOf<String>()
        Logger.setCustomLogger(object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                if (level == Logger.Level.WARN) captured += message
            }
        })
        try {
            block()
        } finally {
            Logger.setCustomLogger(null)
        }
        return captured
    }

    /**
     * BUG: `cancelByTag`/`cancelByWorkerClass` have no-op defaults so that adding them did not
     * break third-party implementations. The defaults were completely silent, which made a
     * scheduler that cancels nothing indistinguishable from one where nothing matched the tag:
     * the caller believes the work is cancelled and it keeps running.
     *
     * FIX: the defaults still do not throw (that is the whole point of having them), but they
     * now emit one WARN naming the implementing class and the member.
     */
    @Test
    fun noOpCancellationDefaults_warnInsteadOfFailingSilently() {
        val scheduler = DefaultsOnlyScheduler()
        val warnings = captureWarnings {
            scheduler.cancelByTag("user-123")
            scheduler.cancelByWorkerClass("SyncWorker")
        }

        assertEquals(2, warnings.size, "each unimplemented member warns once: $warnings")
        assertTrue(
            warnings.any { it.contains("cancelByTag") && it.contains("user-123") },
            "the tag warning must name the member and the argument: $warnings"
        )
        assertTrue(
            warnings.any { it.contains("cancelByWorkerClass") && it.contains("SyncWorker") },
            "the worker-class warning must name the member and the argument: $warnings"
        )
        assertTrue(
            warnings.all { it.contains("DefaultsOnlyScheduler") },
            "the warning must name the implementation that is missing the override: $warnings"
        )
    }

    /**
     * The warning must not become noise: a scheduler that calls cancelByTag in a loop would
     * otherwise flood the log. Only the first call per member warns.
     */
    @Test
    fun noOpCancellationDefaults_warnAtMostOncePerMember() {
        val scheduler = DefaultsOnlyScheduler()
        // Warm up outside the capture — the flag is process-wide, and the previous test may
        // or may not have run first, so this test asserts on the *delta* it can control.
        scheduler.cancelByTag("warm-up")
        scheduler.cancelByWorkerClass("WarmUpWorker")

        val warnings = captureWarnings {
            repeat(20) {
                scheduler.cancelByTag("tag-$it")
                scheduler.cancelByWorkerClass("Worker$it")
            }
        }

        assertTrue(warnings.isEmpty(), "already-warned members must stay quiet: $warnings")
    }

    /**
     * The flag is keyed on implementation + member rather than on member alone: two schedulers
     * in one process must each get told, otherwise whichever one runs second is silent again.
     */
    @Test
    fun noOpCancellationDefaults_warnPerImplementation() {
        DefaultsOnlyScheduler().cancelByTag("first")

        val warnings = captureWarnings {
            OtherDefaultsOnlyScheduler().cancelByTag("second")
        }

        assertEquals(1, warnings.size, "a different implementation warns on its own: $warnings")
        assertTrue(
            warnings.single().contains("OtherDefaultsOnlyScheduler"),
            "the warning must name the second implementation: ${warnings.single()}"
        )
    }
}
