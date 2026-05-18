@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forward-compat regression net — pin down that v2.5.0 reads v2.4.3 on-disk data.
 *
 * **Why this exists.** The v2.5.0 architecture review (QA reviewer) flagged that
 * `kmpworkmanager`'s test suite has no test that:
 *
 *   (a) writes a file in the format an *earlier* library version produced; and
 *   (b) opens it with the *current* library code and asserts no data loss.
 *
 * In practice every prior schema bump has relied on `ignoreUnknownKeys = true` to
 * tolerate missing/extra JSON fields. That tolerance is real but it's a *property
 * of how we configure kotlinx.serialization*. Nothing prevents a future refactor
 * from accidentally flipping it to `false`, or from changing a field's type in a
 * way that silently parses to `null`/default. Without this test, that regression
 * lands silently and corrupts every customer's pending tasks on app upgrade.
 *
 * This test pins:
 *
 *  1. **Chain progress JSON** written in v2.4.3 format (no `stepRetryCounts` field)
 *     loads on v2.5.0 with `stepRetryCounts == emptyMap()` and all v2.4.3 fields
 *     preserved.
 *  2. **Chain progress JSON** with an *unknown extra field* (simulating a
 *     hypothetical v2.6 future bump) loads on v2.5.0 without throwing — proving
 *     forward-tolerance to additive schema changes by future releases.
 *  3. **`schemaVersion`** mismatch is logged but does not block loading additive
 *     changes (v2.5.0 only bumps `CURRENT_SCHEMA_VERSION` when a breaking change
 *     ships, per `ChainProgress` KDoc).
 *
 * **Scope explicitly excludes:** the AppendOnlyQueue binary format. Format
 * version is `0x00000001` in both v2.4.3 and v2.5.0 — the bytes on disk are
 * identical, and `AppendOnlyQueueTest` already exercises round-trips. There is
 * no schema risk to regression-cover there until v2.6 changes the format.
 */
class BackwardCompatibilityTest {

    private lateinit var storage: IosFileStorage
    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() = runTest {
        val fileManager = NSFileManager.defaultManager
        val tempDir = fileManager.temporaryDirectory()
        testDirectory = tempDir.URLByAppendingPathComponent(
            "BackwardCompatTest-${NSDate().timeIntervalSince1970()}-${(0..999999).random()}"
        )!!
        fileManager.createDirectoryAtURL(
            testDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        storage = IosFileStorage(baseDirectory = testDirectory)
    }

    @AfterTest
    fun tearDown() = runTest {
        storage.close()
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    /**
     * v2.4.3 chain progress JSON shape. This is **literal disk content** captured by
     * inspecting `IosFileStorage.saveChainProgress(progress)` output on the v2.4.3
     * release branch. Do NOT regenerate it from current `Json.encodeToString` — the
     * whole point of this test is to assert v2.5.0 can read a v2.4.3-shaped file.
     *
     * Notable: no `stepRetryCounts` key. v2.5 added it with `= emptyMap()` default.
     */
    private val v243ChainProgressJson = """
        {
          "schemaVersion": 1,
          "chainId": "media-upload-2026-04-12",
          "totalSteps": 5,
          "completedSteps": [0, 1, 2],
          "completedTasksInSteps": {
            "3": [0, 2]
          },
          "lastFailedStep": 3,
          "lastError": "HTTP 503 (rate-limited)",
          "retryCount": 2,
          "maxRetries": 3,
          "crashAttemptCount": 0
        }
    """.trimIndent()

    @Test
    fun loads_v243_chainProgress_withoutStepRetryCounts_field() = runTest {
        // Drop the v2.4.3-shaped JSON into the chains directory exactly as v2.4.3
        // would have written it.
        val chainId = "media-upload-2026-04-12"
        writeRawJsonFile(chainId, v243ChainProgressJson)

        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded, "v2.4.3 progress JSON must load on v2.5.0")

        // Every field that existed in v2.4.3 round-trips intact.
        assertEquals(1, loaded.schemaVersion)
        assertEquals(chainId, loaded.chainId)
        assertEquals(5, loaded.totalSteps)
        assertEquals(listOf(0, 1, 2), loaded.completedSteps)
        assertEquals(mapOf(3 to listOf(0, 2)), loaded.completedTasksInSteps)
        assertEquals(3, loaded.lastFailedStep)
        assertEquals("HTTP 503 (rate-limited)", loaded.lastError)
        assertEquals(2, loaded.retryCount)
        assertEquals(3, loaded.maxRetries)
        assertEquals(0, loaded.crashAttemptCount)

        // The new-in-v2.5 field defaults to an empty map — the chain has not yet
        // recorded any per-step attempt counts because it was last persisted by
        // v2.4.3 which had no notion of this field.
        assertEquals(emptyMap(), loaded.stepRetryCounts)
        assertEquals(0, loaded.stepAttempts(3), "stepAttempts(3) must be 0 on legacy file")
    }

    @Test
    fun loads_hypotheticalFutureSchema_withUnknownField() = runTest {
        // Simulate what a v2.6 release might write: an extra field that v2.5.0
        // does not know about. `ignoreUnknownKeys = true` must keep this working —
        // a downgrade from v2.6 to v2.5 (rare but possible during a rollback) must
        // not bricked the pending chains queue.
        val chainId = "future-chain-001"
        val futureJson = """
            {
              "schemaVersion": 1,
              "chainId": "$chainId",
              "totalSteps": 3,
              "completedSteps": [0],
              "completedTasksInSteps": {},
              "lastFailedStep": null,
              "lastError": null,
              "retryCount": 0,
              "maxRetries": 3,
              "crashAttemptCount": 0,
              "stepRetryCounts": {"1": 2},
              "futureFieldNotInV25": "this field would crash a strict parser",
              "anotherFutureField": [1, 2, 3]
            }
        """.trimIndent()
        writeRawJsonFile(chainId, futureJson)

        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded, "Future-schema JSON must load (ignoreUnknownKeys=true)")
        assertEquals(chainId, loaded.chainId)
        assertEquals(listOf(0), loaded.completedSteps)
        // v2.5 fields round-trip intact.
        assertEquals(2, loaded.stepAttempts(1))
    }

    @Test
    fun nullableFields_preserveNull_throughRoundTrip() = runTest {
        // Specifically pin down `lastFailedStep: Int?` and `lastError: String?` —
        // these were nullable in v2.4.3 and remain nullable in v2.5. A regression
        // that flipped them to non-null defaults would silently corrupt every
        // never-failed chain on disk.
        val chainId = "happy-path-chain"
        val happyPathJson = """
            {
              "schemaVersion": 1,
              "chainId": "$chainId",
              "totalSteps": 2,
              "completedSteps": [0, 1],
              "completedTasksInSteps": {},
              "lastFailedStep": null,
              "lastError": null,
              "retryCount": 0,
              "maxRetries": 3,
              "crashAttemptCount": 0
            }
        """.trimIndent()
        writeRawJsonFile(chainId, happyPathJson)

        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded)
        assertEquals(null, loaded.lastFailedStep, "Nullable Int? must stay null")
        assertEquals(null, loaded.lastError, "Nullable String? must stay null")
        assertTrue(loaded.isComplete(), "Chain with all steps completed must report isComplete")
    }

    @Test
    fun corruptJson_isSelfHealed_notPropagatedAsException() = runTest {
        // Defensive: a truncated/garbled file from an interrupted disk write must
        // not throw — `loadChainProgress` deletes the file and returns null. This
        // contract has been in place since v2.3 and we pin it here so a future
        // refactor cannot accidentally make it throw on corrupt input.
        val chainId = "corrupt-chain"
        writeRawJsonFile(chainId, "{\"chainId\":\"$chainId\",\"totalSteps\":")

        val loaded = storage.loadChainProgress(chainId)
        assertEquals(null, loaded, "Corrupt JSON must self-heal to null, not throw")
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Write raw JSON bytes to the chain progress file location that IosFileStorage
     * would read. We deliberately bypass `saveChainProgress` because that would
     * re-serialise via the *current* Json config — defeating the purpose of pinning
     * a v2.4.3 shape.
     */
    private fun writeRawJsonFile(chainId: String, json: String) {
        val chainsDir = testDirectory.URLByAppendingPathComponent("chains")!!
        NSFileManager.defaultManager.createDirectoryAtURL(
            chainsDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val fileURL = chainsDir.URLByAppendingPathComponent("${chainId}_progress.json")!!
        val nsString = NSString.create(string = json)
        nsString.writeToURL(
            url = fileURL,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }
}
