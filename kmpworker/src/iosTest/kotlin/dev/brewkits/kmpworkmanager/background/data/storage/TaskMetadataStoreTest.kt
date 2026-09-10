@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [TaskMetadataStore], the Stage 2 extraction from `IosFileStorage`
 * (see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * The behaviours pinned here are the ones the move had to preserve: one-time and periodic
 * metadata living in separate directories under the same id, self-healing a corrupt file
 * instead of wedging the task forever, the raw-vs-decoded asymmetry between [listTaskIds]
 * and its decoded siblings (a historical compatibility wart that a refactor could silently
 * "clean up" and break `cancelByTag`), the tag/worker scan that `cancelByTag` depends on,
 * and the stale-file reaper's cutoff.
 *
 * See `ChainProgressStoreTest`'s KDoc for why every test runs against both values of
 * `coordinatorTestMode` and what that does — and does not — prove.
 */
class TaskMetadataStoreTest {

    private lateinit var testDirectory: NSURL
    private lateinit var tasksDir: NSURL
    private lateinit var periodicDir: NSURL

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        testDirectory = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}TaskMetadataStoreTest-$stamp-${platform.posix.rand()}"
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    /**
     * Each iteration gets its own pair of directories. Several of these tests assert on a
     * *directory scan* (`listTaskIds`, `findTaskIdsByWorkerOrTag`, `cleanupStaleMetadata`),
     * so sharing one directory across the two iterations would let the first iteration's
     * files show up in the second one's results.
     */
    private fun bothStrategies(block: (TaskMetadataStore, String) -> Unit) {
        listOf(true, false).forEach { flag ->
            tasksDir = testDirectory.safeAppend("tasks-$flag")
            periodicDir = testDirectory.safeAppend("periodic-$flag")
            listOf(tasksDir, periodicDir).forEach {
                NSFileManager.defaultManager.createDirectoryAtURL(
                    it,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }
            val store = TaskMetadataStore(
                io = StorageFileIo(
                    isTestMode = true,
                    coordinatorTestMode = flag,
                    coordinationTimeoutMs = 30_000L
                ),
                persistenceJson = Json { ignoreUnknownKeys = true },
                tasksDir = { tasksDir },
                periodicDir = { periodicDir }
            )
            block(store, "coordinatorTestMode=$flag")
        }
    }

    private fun metaFile(id: String, periodic: Boolean): NSURL =
        (if (periodic) periodicDir else tasksDir).safeAppend("${id.encodeAsPathComponent()}.json")

    private fun exists(id: String, periodic: Boolean): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(metaFile(id, periodic).path!!)

    @Test
    fun metadataRoundTrips() {
        bothStrategies { store, label ->
            val id = "round-trip-$label"
            val meta = mapOf("workerClassName" to "SyncWorker", "inputJson" to "null")
            store.saveTaskMetadata(id, meta, periodic = false)

            assertEquals(meta, store.loadTaskMetadata(id, periodic = false), label)
        }
    }

    /**
     * The same id can legitimately exist as both a one-time and a periodic task; the two
     * directories are the only thing keeping them apart. `deleteTaskMetadata` takes a
     * `periodic` flag precisely because it must not reach across.
     */
    @Test
    fun oneTimeAndPeriodicMetadataForTheSameIdAreIndependent() {
        bothStrategies { store, label ->
            val id = "dual-$label"
            store.saveTaskMetadata(id, mapOf("kind" to "one-time"), periodic = false)
            store.saveTaskMetadata(id, mapOf("kind" to "periodic"), periodic = true)

            assertEquals("one-time", store.loadTaskMetadata(id, false)?.get("kind"), label)
            assertEquals("periodic", store.loadTaskMetadata(id, true)?.get("kind"), label)

            store.deleteTaskMetadata(id, periodic = false)

            assertNull(store.loadTaskMetadata(id, false), "one-time should be gone ($label)")
            assertNotNull(store.loadTaskMetadata(id, true), "periodic must survive ($label)")
        }
    }

    /** A missing metadata file is absence, not an error. */
    @Test
    fun missingMetadataLoadsAsNull() {
        bothStrategies { store, label ->
            assertNull(store.loadTaskMetadata("never-saved-$label", periodic = false), label)
        }
    }

    /**
     * A corrupt sidecar must not be able to wedge a task forever: it is deleted so the task
     * can be rescheduled, rather than throwing on every load.
     */
    @Test
    fun corruptMetadataIsDeletedAndReadsAsNull() {
        bothStrategies { store, label ->
            val id = "corrupt-$label"
            ("{ not json at all" as NSString).writeToFile(
                metaFile(id, periodic = false).path!!,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            assertTrue(exists(id, false), "test setup failed ($label)")

            assertNull(store.loadTaskMetadata(id, periodic = false), label)
            assertFalse(exists(id, false), "corrupt file must be deleted ($label)")
        }
    }

    /**
     * `listTaskIds` returns the raw on-disk name while the "Decoded" variants reverse the
     * path encoding. That asymmetry is deliberate and load-bearing — `listTaskIds`' existing
     * caller compares against on-disk names — so it is pinned here to stop a later cleanup
     * from quietly unifying them.
     */
    @Test
    fun listTaskIdsStaysEncodedWhileTheDecodedVariantDoesNot() {
        bothStrategies { store, label ->
            val hostileId = "a/b-$label"
            store.saveTaskMetadata(hostileId, mapOf("k" to "v"), periodic = false)

            assertTrue(
                store.listTaskIds().contains(hostileId.encodeAsPathComponent()),
                "listTaskIds must return the raw on-disk name ($label)"
            )
            assertTrue(
                store.listOneTimeTaskIdsDecoded().contains(hostileId),
                "the decoded variant must return the original id ($label)"
            )
        }
    }

    /** Periodic listings read the periodic directory, and only that one. */
    @Test
    fun periodicListingSeesOnlyPeriodicTasks() {
        bothStrategies { store, label ->
            store.saveTaskMetadata("one-time-$label", mapOf("k" to "v"), periodic = false)
            store.saveTaskMetadata("periodic-$label", mapOf("k" to "v"), periodic = true)

            val periodic = store.listPeriodicTaskIds().toList()
            assertTrue(periodic.contains("periodic-$label"), label)
            assertFalse(periodic.contains("one-time-$label"), label)
        }
    }

    /**
     * `cancelByTag` and `cancelByWorkerClass` are built on this scan; it has to reach both
     * directories and report which one each hit came from, because the caller deletes the
     * metadata file next and the two directories can hold the same id.
     */
    @Test
    fun theScanFindsTasksByWorkerAndByTagAcrossBothDirectories() {
        bothStrategies { store, label ->
            store.saveTaskMetadata(
                "sync-$label",
                mapOf("workerClassName" to "SyncWorker", "kmpTags" to "user-123,nightly"),
                periodic = false
            )
            store.saveTaskMetadata(
                "cleanup-$label",
                mapOf("workerClassName" to "CleanupWorker", "kmpTags" to "user-123"),
                periodic = true
            )
            store.saveTaskMetadata(
                "unrelated-$label",
                mapOf("workerClassName" to "OtherWorker", "kmpTags" to "someone-else"),
                periodic = false
            )

            val byWorker = store.findTaskIdsByWorkerOrTag(workerClassName = "SyncWorker")
            assertEquals(listOf("sync-$label" to false), byWorker, label)

            val byTag = store.findTaskIdsByWorkerOrTag(tag = "user-123").toSet()
            assertEquals(
                setOf("sync-$label" to false, "cleanup-$label" to true),
                byTag,
                "the tag scan must cross both directories and keep the periodic flag ($label)"
            )
        }
    }

    /** A tag must match a whole comma-separated entry, not a substring of one. */
    @Test
    fun theTagScanDoesNotMatchAPartialTag() {
        bothStrategies { store, label ->
            store.saveTaskMetadata(
                "task-$label",
                mapOf("workerClassName" to "SyncWorker", "kmpTags" to "user-1234"),
                periodic = false
            )

            assertTrue(store.findTaskIdsByWorkerOrTag(tag = "user-123").isEmpty(), label)
            assertEquals(1, store.findTaskIdsByWorkerOrTag(tag = "user-1234").size, label)
        }
    }

    /** Asking for nothing returns nothing rather than everything. */
    @Test
    fun theScanWithNoCriteriaReturnsEmpty() {
        bothStrategies { store, label ->
            store.saveTaskMetadata("task-$label", mapOf("workerClassName" to "W"), periodic = false)
            assertTrue(store.findTaskIdsByWorkerOrTag().isEmpty(), label)
        }
    }

    /**
     * The reaper's cutoff is the file's modification date. A fresh task must survive it —
     * deleting live metadata would make a scheduled task un-runnable.
     */
    @Test
    fun theReaperDeletesStaleMetadataAndKeepsFreshMetadata() {
        bothStrategies { store, label ->
            val stale = "stale-$label"
            val fresh = "fresh-$label"
            store.saveTaskMetadata(stale, mapOf("k" to "v"), periodic = false)
            store.saveTaskMetadata(fresh, mapOf("k" to "v"), periodic = false)

            // Backdate the stale file by 30 days.
            NSFileManager.defaultManager.setAttributes(
                mapOf<Any?, Any?>(
                    NSFileModificationDate to NSDate().dateByAddingTimeInterval(-30.0 * 86400)
                ),
                ofItemAtPath = metaFile(stale, periodic = false).path!!,
                error = null
            )

            store.cleanupStaleMetadata(olderThanDays = 7)

            assertFalse(exists(stale, false), "metadata older than the cutoff must go ($label)")
            assertTrue(exists(fresh, false), "live metadata must survive the reaper ($label)")
        }
    }
}
