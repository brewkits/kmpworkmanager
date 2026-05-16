@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package dev.brewkits.kmpworkmanager.workers.builtins

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression coverage for the v2.5.0 fix to the
 * `IosBackgroundUrlSessionManager` cold-launch bug.
 *
 * **The bug** (QA review): prior to v2.5.0 the manager held `savePaths` /
 * `taskNames` only in process memory. When iOS killed the app and cold-launched
 * it later to deliver a `URLSession` delegate callback, the maps were empty and
 * the downloaded file was orphaned. These tests prove the new
 * `BackgroundDownloadStateStore` survives that scenario.
 *
 * **What we cannot test from a JVM/K-N test harness:** real cold-launch. iOS
 * actually killing and re-spawning the process is out of scope for unit tests.
 * We simulate it with [BackgroundDownloadStateStore.invalidateInMemoryCacheForTest]
 * — the equivalent of "this is a fresh process, the cache is empty, read from
 * disk like you would on a cold-launch". If a future regression broke the disk
 * persistence layer, the cache invalidation would expose it here.
 *
 * Each test owns its entries by using a unique session-id prefix so they can
 * run in parallel without cross-pollution.
 */
class BackgroundDownloadStateStoreTest {

    private val testRunId: String = "test-${kotlin.random.Random.nextInt(0, 1_000_000)}"

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        // Clear any leftover entries from prior runs in this process.
        BackgroundDownloadStateStore.clearForTest()
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        BackgroundDownloadStateStore.clearForTest()
    }

    @Test
    fun putThenGet_returnsPersistedEntry() = runBlocking<Unit> {
        val entry = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-photos",
            taskIdentifier = 42L,
            savePath = "/var/mobile/Documents/photo-001.heic",
            workerName = "PhotoBackupWorker",
            createdAtMs = 1_700_000_000_000L,
        )
        BackgroundDownloadStateStore.put(entry)

        val loaded = BackgroundDownloadStateStore.get("$testRunId-photos", 42L)
        assertEquals(entry, loaded)
    }

    @Test
    fun coldLaunch_simulationLoadsFromDisk_notInMemoryMap() = runBlocking<Unit> {
        // This is the regression net for the original bug. Sequence:
        //  1. v2.5 enqueues a download — entry persisted.
        //  2. (simulated) App is force-quit; in-memory cache is gone.
        //  3. iOS relaunches the app; delegate fires.
        //  4. getSync (called from delegate) must hit disk because the cache is empty.
        val entry = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-coldlaunch",
            taskIdentifier = 7L,
            savePath = "/var/mobile/Documents/coldlaunch.mp4",
            workerName = "VideoUploadWorker",
            createdAtMs = 1_700_000_000_000L,
        )
        BackgroundDownloadStateStore.put(entry)

        // Simulate a fresh process: drop the in-memory cache.
        BackgroundDownloadStateStore.invalidateInMemoryCacheForTest()

        // getSync is what the NSURLSession delegate uses. It MUST find the entry
        // even though the in-memory cache was just wiped — the whole point of v2.5.
        val recovered = BackgroundDownloadStateStore.getSync("$testRunId-coldlaunch", 7L)
        assertNotNull(recovered, "After cold-launch simulation, getSync must hit disk")
        assertEquals(entry.savePath, recovered.savePath)
        assertEquals(entry.workerName, recovered.workerName)
    }

    @Test
    fun sessionIdAndTaskId_areDisambiguated() = runBlocking<Unit> {
        // iOS recycles task IDs per session. Two different sessions can both have
        // taskIdentifier=1 in flight at once. The store must key by the full
        // composite (sessionId, taskId) to avoid collisions.
        val photoTask1 = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-photos",
            taskIdentifier = 1L,
            savePath = "/var/photo-1.heic",
            workerName = "PhotoWorker",
            createdAtMs = 1L,
        )
        val videoTask1 = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-videos",
            taskIdentifier = 1L, // SAME task identifier, DIFFERENT session
            savePath = "/var/video-1.mp4",
            workerName = "VideoWorker",
            createdAtMs = 2L,
        )
        BackgroundDownloadStateStore.put(photoTask1)
        BackgroundDownloadStateStore.put(videoTask1)

        // Each must round-trip independently.
        assertEquals("/var/photo-1.heic", BackgroundDownloadStateStore.get("$testRunId-photos", 1L)?.savePath)
        assertEquals("/var/video-1.mp4", BackgroundDownloadStateStore.get("$testRunId-videos", 1L)?.savePath)
    }

    @Test
    fun remove_isIdempotent_andSurvivesProcessRestart() = runBlocking<Unit> {
        val entry = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-remove",
            taskIdentifier = 99L,
            savePath = "/tmp/remove-me.bin",
            workerName = "TestWorker",
            createdAtMs = 1L,
        )
        BackgroundDownloadStateStore.put(entry)
        assertNotNull(BackgroundDownloadStateStore.get("$testRunId-remove", 99L))

        BackgroundDownloadStateStore.remove("$testRunId-remove", 99L)
        assertNull(BackgroundDownloadStateStore.get("$testRunId-remove", 99L))

        // Idempotent: removing again is a no-op (no exception).
        BackgroundDownloadStateStore.remove("$testRunId-remove", 99L)

        // Survives "cold launch": after cache invalidation, the entry is still gone
        // because the remove was persisted to disk.
        BackgroundDownloadStateStore.invalidateInMemoryCacheForTest()
        assertNull(BackgroundDownloadStateStore.getSync("$testRunId-remove", 99L))
    }

    @Test
    fun sweepStale_dropsOldEntries_keepsFreshOnes() = runBlocking<Unit> {
        val nowMs = 10_000_000L
        val maxAge = 60_000L

        val old = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-sweep",
            taskIdentifier = 1L,
            savePath = "/tmp/old",
            workerName = "Old",
            createdAtMs = nowMs - maxAge - 1L, // 1 ms past cutoff
        )
        val fresh = BackgroundDownloadStateStore.Entry(
            sessionIdentifier = "$testRunId-sweep",
            taskIdentifier = 2L,
            savePath = "/tmp/fresh",
            workerName = "Fresh",
            createdAtMs = nowMs - (maxAge / 2L), // well within
        )
        BackgroundDownloadStateStore.put(old)
        BackgroundDownloadStateStore.put(fresh)

        BackgroundDownloadStateStore.sweepStale(maxAge, nowMs)

        assertNull(BackgroundDownloadStateStore.get("$testRunId-sweep", 1L), "Stale entry must be swept")
        assertNotNull(BackgroundDownloadStateStore.get("$testRunId-sweep", 2L), "Fresh entry must survive sweep")
    }
}
