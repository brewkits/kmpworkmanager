@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.InsufficientDiskSpaceException
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [StorageMaintenance], the Stage 4 extraction from `IosFileStorage`
 * (see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * Covers the disk-space guard that gates every definition write and the maintenance run's
 * bookkeeping — including the property that makes a failed run safe: it must stay overdue
 * rather than record itself as done.
 */
class StorageMaintenanceTest {

    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        testDirectory = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}StorageMaintenanceTest-$stamp-${platform.posix.rand()}"
        )
        NSFileManager.defaultManager.createDirectoryAtURL(
            testDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    private fun newMaintenance(diskSpaceBufferBytes: Long = 0L) = StorageMaintenance(
        io = StorageFileIo(
            isTestMode = true,
            coordinatorTestMode = true,
            coordinationTimeoutMs = 30_000L
        ),
        baseDir = { testDirectory },
        maintenanceTimestampFile = { testDirectory.safeAppend("last_maintenance.txt") },
        diskSpaceBufferBytes = diskSpaceBufferBytes,
        isTestMode = true
    )

    @Test
    fun anOrdinaryWriteClearsTheDiskSpaceGuard() {
        newMaintenance().checkDiskSpace(requiredBytes = 1024)
    }

    /**
     * The guard demands `requiredBytes + buffer`. Asking for more than any device has must
     * throw rather than let the write proceed and fail halfway through.
     */
    @Test
    fun animpossiblyLargeRequestIsRefused() {
        var thrown: InsufficientDiskSpaceException? = null
        try {
            // 1 EB — larger than any filesystem this can run on.
            newMaintenance().checkDiskSpace(requiredBytes = 1_000_000_000_000_000_000L)
        } catch (e: InsufficientDiskSpaceException) {
            thrown = e
        }
        assertTrue(thrown != null, "the guard must refuse an impossible request")
    }

    /** The configured buffer is headroom on top of the request, not instead of it. */
    @Test
    fun theBufferIsAddedOnTopOfTheRequestedSize() {
        var thrown = false
        try {
            newMaintenance(diskSpaceBufferBytes = 1_000_000_000_000_000_000L)
                .checkDiskSpace(requiredBytes = 1)
        } catch (e: InsufficientDiskSpaceException) {
            thrown = true
        }
        assertTrue(thrown, "a 1-byte write must fail when the buffer alone exceeds the disk")
    }

    @Test
    fun maintenanceThatHasNeverRunIsOverdue() {
        val maintenance = newMaintenance()
        assertEquals(Int.MAX_VALUE, maintenance.hoursSinceLastMaintenance())
        assertTrue(maintenance.isMaintenanceRequired(hoursInterval = 24))
    }

    /** A zero interval always runs — the documented escape hatch for tests. */
    @Test
    fun aZeroIntervalAlwaysRequiresMaintenance() {
        val maintenance = newMaintenance()
        maintenance.runMaintenance(emptyList())
        assertTrue(maintenance.isMaintenanceRequired(hoursInterval = 0))
    }

    @Test
    fun aSuccessfulRunExecutesEveryCleanupAndStopsBeingOverdue() {
        val maintenance = newMaintenance()
        val ran = mutableListOf<String>()

        maintenance.runMaintenance(listOf({ ran += "markers" }, { ran += "metadata" }))

        assertEquals(listOf("markers", "metadata"), ran)
        assertEquals(0, maintenance.hoursSinceLastMaintenance())
        assertFalse(maintenance.isMaintenanceRequired(hoursInterval = 24))
    }

    /**
     * Maintenance is opportunistic housekeeping launched from an init block. A reaper that
     * throws must not take the storage instance — or app launch — down with it, and must not
     * record itself as done: a run that failed halfway has not cleaned up, so it needs to
     * stay overdue and be retried.
     */
    @Test
    fun aFailingCleanupIsSwallowedAndLeavesMaintenanceOverdue() {
        val maintenance = newMaintenance()

        maintenance.runMaintenance(listOf({ error("reaper exploded") }))

        assertEquals(
            Int.MAX_VALUE,
            maintenance.hoursSinceLastMaintenance(),
            "a failed run must not be recorded as completed"
        )
        assertTrue(maintenance.isMaintenanceRequired(hoursInterval = 24))
    }
}
