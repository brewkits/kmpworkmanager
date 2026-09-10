@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
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
 * Contract tests for [ChainDefinitionStore], the Stage 4 extraction from `IosFileStorage`
 * (see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * Two groups live in this store and both are pinned here: the definitions themselves — with
 * the size ceiling and the self-heal that must also drop the chain's progress — and the
 * REPLACE-policy deleted markers, whose whole job is to stop a superseded chain from running.
 *
 * See `ChainProgressStoreTest`'s KDoc for the coordination-flag parameterisation and its
 * limits.
 */
class ChainDefinitionStoreTest {

    private lateinit var testDirectory: NSURL
    private lateinit var chainsDir: NSURL
    private lateinit var deletedDir: NSURL
    private val progressDeletions = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        testDirectory = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}ChainDefinitionStoreTest-$stamp-${platform.posix.rand()}"
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    /** Fresh directories per iteration — several tests here assert on a directory scan. */
    private suspend fun bothStrategies(block: suspend (ChainDefinitionStore, String) -> Unit) {
        listOf(true, false).forEach { flag ->
            chainsDir = testDirectory.safeAppend("chains-$flag")
            deletedDir = testDirectory.safeAppend("deleted-$flag")
            listOf(chainsDir, deletedDir).forEach {
                NSFileManager.defaultManager.createDirectoryAtURL(
                    it,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }
            progressDeletions.clear()
            val io = StorageFileIo(
                isTestMode = true,
                coordinatorTestMode = flag,
                coordinationTimeoutMs = 30_000L
            )
            val store = ChainDefinitionStore(
                io = io,
                maintenance = StorageMaintenance(
                    io = io,
                    baseDir = { testDirectory },
                    maintenanceTimestampFile = { testDirectory.safeAppend("last_maintenance.txt") },
                    diskSpaceBufferBytes = 0L,
                    isTestMode = true
                ),
                persistenceJson = Json { ignoreUnknownKeys = true },
                chainsDir = { chainsDir },
                deletedChainsDir = { deletedDir },
                maxChainSizeBytes = 10_485_760L,
                deletedMarkerMaxAgeMs = 7L * 24 * 60 * 60 * 1000,
                deleteProgress = { id -> progressDeletions += id }
            )
            block(store, "coordinatorTestMode=$flag")
        }
    }

    private fun definitionFile(id: String): NSURL =
        chainsDir.safeAppend("${id.encodeAsPathComponent()}.json")

    private fun steps(vararg workers: String): List<List<TaskRequest>> =
        workers.map { listOf(TaskRequest(workerClassName = it)) }

    @Test
    fun definitionsRoundTrip() = runTest {
        bothStrategies { store, label ->
            val id = "round-trip-$label"
            store.saveChainDefinition(id, steps("A", "B", "C"))

            val loaded = store.loadChainDefinition(id)
            assertNotNull(loaded, label)
            assertEquals(3, loaded.size, label)
            assertEquals(listOf("A", "B", "C"), loaded.map { it.single().workerClassName }, label)
        }
    }

    @Test
    fun chainExistsTracksTheDefinitionFile() = runTest {
        bothStrategies { store, label ->
            val id = "exists-$label"
            assertFalse(store.chainExists(id), label)

            store.saveChainDefinition(id, steps("A"))
            assertTrue(store.chainExists(id), label)

            store.deleteChainDefinition(id)
            assertFalse(store.chainExists(id), label)
            assertNull(store.loadChainDefinition(id), label)
        }
    }

    /**
     * The ceiling is measured in UTF-8 bytes, not `String.length`. CJK and emoji content is
     * 3–4 bytes per char, so a char-count check would let a ~15 MB write through a 10 MB
     * limit — the bug this check was rewritten to fix.
     */
    @Test
    fun anOversizeDefinitionIsRefused() = runTest {
        bothStrategies { store, label ->
            // 20 000 steps of a long CJK worker name: well past the 10 MB ceiling in bytes.
            val huge = (0 until 20_000).map {
                listOf(TaskRequest(workerClassName = "任务处理器".repeat(40) + it))
            }
            var threw = false
            try {
                store.saveChainDefinition("huge-$label", huge)
            } catch (e: IllegalStateException) {
                threw = true
            }
            assertTrue(threw, "an oversize chain must be refused ($label)")
            assertFalse(store.chainExists("huge-$label"), "nothing must be written ($label)")
        }
    }

    /**
     * A corrupt definition takes its progress with it. Otherwise the chain resumes at step N
     * against a definition that no longer exists — the progress says "3 of 5 done" for a
     * chain nothing can describe any more.
     */
    @Test
    fun aCorruptDefinitionIsDeletedAlongWithItsProgress() = runTest {
        bothStrategies { store, label ->
            val id = "corrupt-$label"
            ("[[not valid json" as NSString).writeToFile(
                definitionFile(id).path!!,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            assertTrue(store.chainExists(id), "test setup failed ($label)")

            assertNull(store.loadChainDefinition(id), label)
            assertFalse(store.chainExists(id), "corrupt definition must be deleted ($label)")
            assertEquals(
                listOf(id),
                progressDeletions,
                "self-healing a definition must also drop its progress ($label)"
            )
        }
    }

    /** A missing definition is absence, not corruption — no progress is dropped. */
    @Test
    fun aMissingDefinitionLoadsAsNullWithoutTouchingProgress() = runTest {
        bothStrategies { store, label ->
            assertNull(store.loadChainDefinition("absent-$label"), label)
            assertTrue(progressDeletions.isEmpty(), "absence must not drop progress ($label)")
        }
    }

    /** The marker lifecycle REPLACE depends on: set, observe, clear. */
    @Test
    fun deletedMarkersAreSetObservedAndCleared() = runTest {
        bothStrategies { store, label ->
            val id = "replaced-$label"
            assertFalse(store.isChainDeleted(id), label)

            store.markChainAsDeleted(id)
            assertTrue(store.isChainDeleted(id), "the marker must be visible ($label)")

            store.clearDeletedMarker(id)
            assertFalse(store.isChainDeleted(id), "the marker must be clearable ($label)")
        }
    }

    /**
     * Markers are reaped by age so they cannot leak disk space, but a *fresh* marker must
     * survive: deleting it early would let a replaced chain execute after all, which is the
     * exact duplicate-execution the marker exists to prevent.
     */
    @Test
    fun theReaperAgesOutOldMarkersAndKeepsFreshOnes() = runTest {
        bothStrategies { store, label ->
            val fresh = "fresh-$label"
            store.markChainAsDeleted(fresh)

            // A marker whose recorded timestamp is 30 days old, written the way the
            // production path writes one (seconds since epoch, as text).
            val stale = "stale-$label"
            val thirtyDaysAgo = NSDate().timeIntervalSince1970.toLong() - 30L * 86400
            (thirtyDaysAgo.toString() as NSString).writeToFile(
                deletedDir.safeAppend("${stale.encodeAsPathComponent()}.marker").path!!,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            assertTrue(store.isChainDeleted(stale), "test setup failed ($label)")

            store.cleanupStaleDeletedMarkers()

            assertFalse(store.isChainDeleted(stale), "an aged marker must be reaped ($label)")
            assertTrue(store.isChainDeleted(fresh), "a fresh marker must survive ($label)")
        }
    }

    /**
     * The chains directory also holds `<encodedId>_progress.json`. Those are a different
     * artifact and must not be reported as chain definitions — and the ids that are reported
     * come back decoded, so a caller can act on them.
     */
    @Test
    fun theDefinitionListingSkipsProgressFilesAndDecodesIds() = runTest {
        bothStrategies { store, label ->
            val hostileId = "a/b-$label"
            store.saveChainDefinition(hostileId, steps("A"))
            // A progress sidecar for the same chain, as the progress store would write it.
            ("{}" as NSString).writeToFile(
                chainsDir.safeAppend("${hostileId.encodeAsPathComponent()}_progress.json").path!!,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )

            val listed = store.listChainDefinitionIds().toList()
            assertEquals(
                listOf(hostileId),
                listed,
                "only the definition, decoded, must be listed ($label)"
            )
        }
    }
}
