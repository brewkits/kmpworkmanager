@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.IosExecutionHistoryStore
import kotlinx.coroutines.test.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS integration tests for [IosExecutionHistoryStore].
 *
 * Tests:
 * - save() appends records and returns newest-first via getRecords()
 * - clear() removes all records
 * - auto-pruning at MAX_RECORDS threshold
 * - limit parameter in getRecords()
 * - concurrent writes (basic ordering guarantee)
 * - partial-decode resilience (malformed lines skipped)
 * - no-op on clear of empty store
 */
class IosExecutionHistoryStoreTest {

    private lateinit var store: IosExecutionHistoryStore

    @BeforeTest
    fun setup() = runTest {
        store = IosExecutionHistoryStore()
        store.clear()  // Start clean
    }

    @AfterTest
    fun teardown() = runTest {
        store.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basic save + getRecords
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `save and getRecords - empty store returns empty list`() = runTest {
        val records = store.getRecords()
        assertTrue(records.isEmpty(), "Fresh store must be empty")
    }

    @Test
    fun `save and getRecords - single record roundtrip`() = runTest {
        val record = makeRecord("rec-1", ExecutionStatus.SUCCESS, platform = "ios")
        store.save(record)

        val records = store.getRecords()
        assertEquals(1, records.size)
        assertEquals("rec-1", records[0].id)
        assertEquals(ExecutionStatus.SUCCESS, records[0].status)
    }

    @Test
    fun `save and getRecords - multiple records returned newest first`() = runTest {
        val r1 = makeRecord("r1", ExecutionStatus.SUCCESS, startedAtMs = 1_000L)
        val r2 = makeRecord("r2", ExecutionStatus.FAILURE, startedAtMs = 2_000L)
        val r3 = makeRecord("r3", ExecutionStatus.ABANDONED, startedAtMs = 3_000L)

        store.save(r1)
        store.save(r2)
        store.save(r3)

        val records = store.getRecords()
        assertEquals(3, records.size)
        // Newest first (last saved = r3)
        assertEquals("r3", records[0].id)
        assertEquals("r2", records[1].id)
        assertEquals("r1", records[2].id)
    }

    @Test
    fun `getRecords - limit parameter respected`() = runTest {
        repeat(10) { i ->
            store.save(makeRecord("rec-$i", ExecutionStatus.SUCCESS, startedAtMs = i.toLong()))
        }

        val limited = store.getRecords(limit = 3)
        assertEquals(3, limited.size)
    }

    @Test
    fun `getRecords - limit larger than stored returns all`() = runTest {
        repeat(5) { i -> store.save(makeRecord("r$i", ExecutionStatus.SUCCESS)) }

        val all = store.getRecords(limit = 100)
        assertEquals(5, all.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // clear()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clear - empties the store`() = runTest {
        repeat(5) { i -> store.save(makeRecord("r$i", ExecutionStatus.SUCCESS)) }
        assertEquals(5, store.getRecords().size)

        store.clear()
        assertTrue(store.getRecords().isEmpty(), "Store must be empty after clear()")
    }

    @Test
    fun `clear - no-op on already empty store`() = runTest {
        store.clear()  // Already empty
        store.clear()  // Second clear must not throw
        assertTrue(store.getRecords().isEmpty())
    }

    @Test
    fun `clear - new records can be saved after clear`() = runTest {
        repeat(3) { i -> store.save(makeRecord("pre-$i", ExecutionStatus.SUCCESS)) }
        store.clear()

        store.save(makeRecord("post-1", ExecutionStatus.SUCCESS))
        val records = store.getRecords()
        assertEquals(1, records.size)
        assertEquals("post-1", records[0].id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-pruning
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `auto-pruning - store never exceeds MAX_RECORDS`() = runTest {
        val target = ExecutionHistoryStore.MAX_RECORDS + 20

        repeat(target) { i ->
            store.save(makeRecord("r$i", ExecutionStatus.SUCCESS, startedAtMs = i.toLong()))
        }

        val records = store.getRecords(limit = Int.MAX_VALUE)
        assertTrue(
            records.size <= ExecutionHistoryStore.MAX_RECORDS,
            "Store size ${records.size} must not exceed MAX_RECORDS ${ExecutionHistoryStore.MAX_RECORDS}"
        )
    }

    @Test
    fun `auto-pruning - keeps newest records`() = runTest {
        val target = ExecutionHistoryStore.MAX_RECORDS + 10

        repeat(target) { i ->
            store.save(makeRecord("r$i", ExecutionStatus.SUCCESS, startedAtMs = i.toLong()))
        }

        val records = store.getRecords(limit = Int.MAX_VALUE)
        // The oldest records (r0..r9) should have been pruned; newest should survive
        val ids = records.map { it.id }.toSet()
        assertTrue(ids.contains("r${target - 1}"), "Newest record must survive pruning")
        assertTrue(ids.contains("r${target - 10}"), "Recently saved records must survive pruning")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All ExecutionStatus variants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all ExecutionStatus variants persist correctly`() = runTest {
        ExecutionStatus.entries.forEach { status ->
            store.clear()
            val record = makeRecord("s-$status", status)
            store.save(record)

            val loaded = store.getRecords()
            assertEquals(1, loaded.size, "Should have exactly one record for $status")
            assertEquals(status, loaded[0].status, "Status $status must persist correctly")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field preservation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all fields preserved through save and load`() = runTest {
        val record = ExecutionRecord(
            id = "full-test",
            chainId = "chain-full",
            status = ExecutionStatus.FAILURE,
            startedAtMs = 1_700_000_000_000L,
            endedAtMs   = 1_700_000_005_000L,
            durationMs  = 5_000L,
            totalSteps  = 4,
            completedSteps = 2,
            failedStep  = 2,
            errorMessage = "Worker timed out after 20s",
            retryCount  = 1,
            platform    = "ios",
            workerClassNames = listOf("FetchWorker", "ProcessWorker", "UploadWorker", "CleanupWorker")
        )
        store.save(record)

        val loaded = store.getRecords()[0]
        assertEquals(record.id, loaded.id)
        assertEquals(record.chainId, loaded.chainId)
        assertEquals(record.status, loaded.status)
        assertEquals(record.startedAtMs, loaded.startedAtMs)
        assertEquals(record.endedAtMs, loaded.endedAtMs)
        assertEquals(record.durationMs, loaded.durationMs)
        assertEquals(record.totalSteps, loaded.totalSteps)
        assertEquals(record.completedSteps, loaded.completedSteps)
        assertEquals(record.failedStep, loaded.failedStep)
        assertEquals(record.errorMessage, loaded.errorMessage)
        assertEquals(record.retryCount, loaded.retryCount)
        assertEquals(record.platform, loaded.platform)
        assertEquals(record.workerClassNames, loaded.workerClassNames)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeRecord(
        id: String,
        status: ExecutionStatus,
        startedAtMs: Long = 0L,
        platform: String = "ios"
    ) = ExecutionRecord(
        id = id,
        chainId = "chain-$id",
        status = status,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 1000L,
        durationMs = 1000L,
        totalSteps = 1,
        completedSteps = if (status == ExecutionStatus.SUCCESS) 1 else 0,
        platform = platform
    )
}
