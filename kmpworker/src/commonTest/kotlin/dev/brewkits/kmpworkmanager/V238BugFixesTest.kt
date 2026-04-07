package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.BgTaskIdProvider
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskPriority
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for v2.3.8 features and bug fixes.
 *
 * Covers:
 * - ExecutionRecord serialization roundtrip
 * - ExecutionStatus all variants
 * - TaskPriority weight ordering + serialization
 * - BgTaskIdProvider interface contract
 * - TelemetryHook default no-op implementations
 * - TelemetryHook event accumulation pattern
 * - SecurityValidator IPv6 compressed loopback bypass (Fix 6 from v2.3.8)
 * - SecurityValidator multi-@ UserInfo SSRF (Fix 6 from previous commit)
 * - SecurityValidator case-sensitivity in validateFilePath (Fix 5)
 */
class V238BugFixesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // ExecutionRecord — serialization roundtrip
    // ─────────────────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun ExecutionRecord_full_roundtrip_serialization() {
        val original = ExecutionRecord(
            id = "rec-001",
            chainId = "chain-sync",
            status = ExecutionStatus.SUCCESS,
            startedAtMs = 1_700_000_000_000L,
            endedAtMs   = 1_700_000_003_500L,
            durationMs  = 3_500L,
            totalSteps  = 3,
            completedSteps = 3,
            failedStep  = null,
            errorMessage = null,
            retryCount  = 0,
            platform    = "android",
            workerClassNames = listOf("FetchWorker", "ProcessWorker", "UploadWorker")
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ExecutionRecord>(encoded)

        assertEquals(original, decoded)
        assertEquals("rec-001", decoded.id)
        assertEquals(ExecutionStatus.SUCCESS, decoded.status)
        assertEquals(3, decoded.workerClassNames.size)
    }

    @Test
    fun ExecutionRecord_all_ExecutionStatus_variants_serialize_correctly() {
        ExecutionStatus.entries.forEach { status ->
            val record = ExecutionRecord(
                id = "test-$status",
                chainId = "chain",
                status = status,
                startedAtMs = 0L,
                endedAtMs = 1L,
                durationMs = 1L,
                totalSteps = 1,
                completedSteps = if (status == ExecutionStatus.SUCCESS) 1 else 0,
                platform = "ios"
            )
            val decoded = json.decodeFromString<ExecutionRecord>(json.encodeToString(record))
            assertEquals(status, decoded.status, "Status $status must survive roundtrip")
        }
    }

    @Test
    fun ExecutionRecord_optional_fields_default_correctly() {
        val minimal = ExecutionRecord(
            id = "min",
            chainId = "c",
            status = ExecutionStatus.FAILURE,
            startedAtMs = 0L,
            endedAtMs = 0L,
            durationMs = 0L,
            totalSteps = 1,
            completedSteps = 0,
            platform = "android"
        )
        assertNull(minimal.failedStep)
        assertNull(minimal.errorMessage)
        assertEquals(0, minimal.retryCount)
        assertTrue(minimal.workerClassNames.isEmpty())
    }

    @Test
    fun ExecutionRecord_failure_fields_are_preserved() {
        val record = ExecutionRecord(
            id = "fail",
            chainId = "chain",
            status = ExecutionStatus.FAILURE,
            startedAtMs = 1000L,
            endedAtMs = 2000L,
            durationMs = 1000L,
            totalSteps = 5,
            completedSteps = 2,
            failedStep = 2,
            errorMessage = "Network timeout after 20s",
            retryCount = 2,
            platform = "ios",
            workerClassNames = listOf("A", "B", "C", "D", "E")
        )
        val decoded = json.decodeFromString<ExecutionRecord>(json.encodeToString(record))
        assertEquals(2, decoded.failedStep)
        assertEquals("Network timeout after 20s", decoded.errorMessage)
        assertEquals(2, decoded.retryCount)
    }

    @Test
    fun ExecutionRecord_unknown_JSON_fields_are_ignored_on_decode() {
        val jsonWithExtra = """
            {"id":"x","chainId":"c","status":"SUCCESS","startedAtMs":0,"endedAtMs":0,
            "durationMs":0,"totalSteps":1,"completedSteps":1,"platform":"android",
            "unknownFutureField":"some value","anotherNewField":42}
        """.trimIndent()
        val record = json.decodeFromString<ExecutionRecord>(jsonWithExtra)
        assertEquals("x", record.id)
        assertEquals(ExecutionStatus.SUCCESS, record.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TaskPriority — ordering and serialization
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun TaskPriority_weight_ordering_is_LOW_less_than_NORMAL_less_than_HIGH_less_than_CRITICAL() {
        assertTrue(TaskPriority.LOW.weight < TaskPriority.NORMAL.weight,    "LOW < NORMAL")
        assertTrue(TaskPriority.NORMAL.weight < TaskPriority.HIGH.weight,   "NORMAL < HIGH")
        assertTrue(TaskPriority.HIGH.weight < TaskPriority.CRITICAL.weight, "HIGH < CRITICAL")
    }

    @Test
    fun TaskPriority_sorted_list_produces_correct_order() {
        val priorities = listOf(TaskPriority.HIGH, TaskPriority.LOW, TaskPriority.CRITICAL, TaskPriority.NORMAL)
        val sorted = priorities.sortedByDescending { it.weight }
        assertEquals(listOf(TaskPriority.CRITICAL, TaskPriority.HIGH, TaskPriority.NORMAL, TaskPriority.LOW), sorted)
    }

    @Test
    fun TaskPriority_serialization_roundtrip() {
        TaskPriority.entries.forEach { priority ->
            val encoded = json.encodeToString(priority)
            val decoded = json.decodeFromString<TaskPriority>(encoded)
            assertEquals(priority, decoded, "Priority $priority must survive roundtrip")
        }
    }

    @Test
    fun TaskPriority_CRITICAL_and_HIGH_are_expedited_candidates() {
        // Verifies the semantic contract used by Android's setExpedited() logic
        val expedited = TaskPriority.entries.filter { it.weight >= TaskPriority.HIGH.weight }
        assertEquals(2, expedited.size)
        assertTrue(expedited.contains(TaskPriority.HIGH))
        assertTrue(expedited.contains(TaskPriority.CRITICAL))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BgTaskIdProvider — interface contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun BgTaskIdProvider_requiredBgTaskIds_returns_declared_IDs() {
        val provider = object : BgTaskIdProvider {
            override val requiredBgTaskIds: Set<String> = setOf(
                "com.example.sync-task",
                "com.example.upload-task"
            )
        }
        assertEquals(2, provider.requiredBgTaskIds.size)
        assertTrue(provider.requiredBgTaskIds.contains("com.example.sync-task"))
        assertTrue(provider.requiredBgTaskIds.contains("com.example.upload-task"))
    }

    @Test
    fun BgTaskIdProvider_missing_IDs_detection_pattern() {
        val factory = object : BgTaskIdProvider {
            override val requiredBgTaskIds: Set<String> = setOf("com.app.sync", "com.app.upload")
        }
        val permittedByInfoPlist = setOf("com.app.sync") // upload is missing
        val missing = factory.requiredBgTaskIds - permittedByInfoPlist
        assertEquals(setOf("com.app.upload"), missing)
    }

    @Test
    fun BgTaskIdProvider_empty_set_means_no_validation_required() {
        val factory = object : BgTaskIdProvider {
            override val requiredBgTaskIds: Set<String> = emptySet()
        }
        val permittedByInfoPlist = setOf<String>()
        val missing = factory.requiredBgTaskIds - permittedByInfoPlist
        assertTrue(missing.isEmpty(), "No IDs declared = no validation needed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TelemetryHook — default no-ops + event accumulation pattern
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun TelemetryHook_default_implementations_are_no_ops() {
        // Verifies all default methods exist and don't throw
        val hook = object : TelemetryHook {}
        hook.onTaskStarted(TelemetryHook.TaskStartedEvent("W", platform = "android", startedAtMs = 0L))
        hook.onTaskCompleted(TelemetryHook.TaskCompletedEvent("W", platform = "android", success = true, durationMs = 100L))
        hook.onTaskFailed(TelemetryHook.TaskFailedEvent("W", platform = "android", error = "oops", durationMs = 0L, retryCount = 0))
        hook.onChainCompleted(TelemetryHook.ChainCompletedEvent("C", totalSteps = 1, platform = "ios", durationMs = 500L))
        hook.onChainFailed(TelemetryHook.ChainFailedEvent("C", failedStep = 0, platform = "ios", error = "fail", retryCount = 0, willRetry = false))
        hook.onChainSkipped(TelemetryHook.ChainSkippedEvent("C", platform = "ios", reason = "REPLACE_POLICY"))
    }

    @Test
    fun TelemetryHook_event_accumulation_pattern_used_for_analytics() {
        data class Event(val type: String, val name: String)
        val events = mutableListOf<Event>()

        val hook = object : TelemetryHook {
            override fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) {
                events.add(Event("started", event.taskName))
            }
            override fun onTaskCompleted(event: TelemetryHook.TaskCompletedEvent) {
                events.add(Event("completed", event.taskName))
            }
            override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
                events.add(Event("failed", event.taskName))
            }
            override fun onChainCompleted(event: TelemetryHook.ChainCompletedEvent) {
                events.add(Event("chain_completed", event.chainId))
            }
            override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
                events.add(Event("chain_failed", event.chainId))
            }
            override fun onChainSkipped(event: TelemetryHook.ChainSkippedEvent) {
                events.add(Event("chain_skipped", event.chainId))
            }
        }

        hook.onTaskStarted(TelemetryHook.TaskStartedEvent("SyncWorker", platform = "android", startedAtMs = 0L))
        hook.onTaskCompleted(TelemetryHook.TaskCompletedEvent("SyncWorker", platform = "android", success = true, durationMs = 100L))
        hook.onChainCompleted(TelemetryHook.ChainCompletedEvent("chain-1", totalSteps = 1, platform = "android", durationMs = 100L))

        assertEquals(3, events.size)
        assertEquals("started",        events[0].type)
        assertEquals("completed",      events[1].type)
        assertEquals("chain_completed",events[2].type)
        assertEquals("SyncWorker",     events[0].name)
        assertEquals("chain-1",        events[2].name)
    }

    @Test
    fun TelemetryHook_TaskStartedEvent_carries_chainId_and_stepIndex() {
        var capturedEvent: TelemetryHook.TaskStartedEvent? = null
        val hook = object : TelemetryHook {
            override fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) { capturedEvent = event }
        }
        hook.onTaskStarted(
            TelemetryHook.TaskStartedEvent(
                taskName = "HttpRequestWorker",
                chainId = "chain-42",
                stepIndex = 3,
                platform = "ios",
                startedAtMs = 1_700_000_000_000L
            )
        )
        assertNotNull(capturedEvent)
        assertEquals("chain-42", capturedEvent!!.chainId)
        assertEquals(3, capturedEvent!!.stepIndex)
    }

    @Test
    fun TelemetryHook_ChainFailedEvent_willRetry_flag() {
        var willRetry: Boolean? = null
        val hook = object : TelemetryHook {
            override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) { willRetry = event.willRetry }
        }
        hook.onChainFailed(TelemetryHook.ChainFailedEvent(
            chainId = "c", failedStep = 1, platform = "ios",
            error = "timeout", retryCount = 2, willRetry = true
        ))
        assertEquals(true, willRetry)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SecurityValidator — IPv6 compressed loopback (Fix v2.3.8)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun SecurityValidator_IPv6_compressed_loopback_forms_should_be_blocked() {
        // Standard compressed form — already caught by explicit check
        assertFalse(SecurityValidator.validateURL("http://[::1]/api"),              "::1 must be blocked")

        // Partial compressed forms that expand to 0:0:0:0:0:0:0:1 (loopback)
        assertFalse(SecurityValidator.validateURL("http://[0::1]/"),               "0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[::0:0:0:1]/"),          "::0:0:0:1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0::1]/"),             "0:0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0::1]/"),           "0:0:0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0::1]/"),         "0:0:0:0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0::1]/"),       "0:0:0:0:0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0:0::1]/"),     "0:0:0:0:0:0::1 must be blocked")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0:0:0:1]/"),   "fully expanded loopback must be blocked")

        // Valid public IPv6 must still pass
        assertTrue(SecurityValidator.validateURL("http://[2001:4860:4860::8888]/"), "Google public IPv6 DNS must pass")
        assertTrue(SecurityValidator.validateURL("http://[2606:4700:4700::1111]/"), "Cloudflare public IPv6 DNS must pass")
    }

    @Test
    fun SecurityValidator_expandIPv6_handles_edge_cases_gracefully() {
        // Multiple :: in one address (malformed) — must be rejected
        assertFalse(SecurityValidator.validateURL("http://[::1::1]/"),  "double :: is malformed → reject")
        // Empty address after scheme — must be rejected
        assertFalse(SecurityValidator.validateURL("http://[::]/"),      ":: alone expands to all-zeros, not loopback → pass or reject based on ipv6 check")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SecurityValidator — validateFilePath case-sensitivity (Fix v2.3.8)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun SecurityValidator_uppercase_percent_encoded_path_traversal_is_blocked() {
        assertFalse(SecurityValidator.validateFilePath("%2E%2E/etc/passwd"),   "%2E%2E must be blocked")
        assertFalse(SecurityValidator.validateFilePath("%2E%2E/%2E%2E/root"),  "chained %2E%2E must be blocked")
        assertFalse(SecurityValidator.validateFilePath("%252E%252E/secret"),   "double-encoded %252E must be blocked")
        assertFalse(SecurityValidator.validateFilePath("%2E%2e/mixed"),        "mixed-case must be blocked")
        assertFalse(SecurityValidator.validateFilePath("%5C%2E%2E"),           "%5C followed by %2E%2E must be blocked")
        assertTrue(SecurityValidator.validateFilePath("uploads/photo.jpg"),    "normal path must still pass")
        assertTrue(SecurityValidator.validateFilePath("data/2024/report.pdf"), "nested valid path must pass")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SecurityValidator — multi-@ UserInfo SSRF (Fix v2.3.8)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun SecurityValidator_multi_at_UserInfo_with_private_IP_in_middle_is_blocked() {
        assertFalse(SecurityValidator.validateURL("https://user:pass@127.0.0.1:80@api.example.com/"))
        assertFalse(SecurityValidator.validateURL("http://a@10.0.0.1@external.com/"))
        assertFalse(SecurityValidator.validateURL("http://x@192.168.1.1:8080@b.com/path"))
        assertFalse(SecurityValidator.validateURL("http://y@169.254.169.254@z.com/"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SecurityValidator — DNS rebinding KDoc contract (conceptual test)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun SecurityValidator_public_domains_pass_at_validation_time() {
        // DNS rebinding happens AFTER validation; the validator can only check the hostname
        // as presented. This test documents the known limitation.
        assertTrue(SecurityValidator.validateURL("https://attacker-rebinding.example.com/api"),
            "Public domain passes — DNS rebinding is a network-layer concern, not validatable here")
    }
}
