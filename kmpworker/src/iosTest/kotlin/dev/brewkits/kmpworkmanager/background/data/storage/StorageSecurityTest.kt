@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Security tests for the storage layer extracted in v3.5.0.
 *
 * Chain and task ids are **caller-supplied** — they arrive from `scheduler.enqueue(id, …)`
 * and `TaskChain.withId(…)` — and every store interpolates them straight into a filename.
 * The only thing standing between a hostile id and a write outside the storage directory is
 * `String.encodeAsPathComponent()`. `V331PathTraversalTest` pins the encoder itself and one
 * path through the old façade; this file pins the property that matters after the split:
 * *each extracted store* still applies it, on every path that builds a filename.
 *
 * The assertion is deliberately about the filesystem, not about the encoder's return value.
 * What matters is that nothing lands outside the store's own directory — a property that
 * depends on Foundation's path resolution as much as on the encoder's output string.
 */
class StorageSecurityTest {

    private lateinit var root: NSURL
    private lateinit var storeDir: NSURL
    private lateinit var siblingDir: NSURL
    private lateinit var scope: CoroutineScope

    /**
     * Ids that try to leave the directory, name a reserved entry, or smuggle a separator.
     * Each must be storable and readable back without touching anything outside [storeDir].
     */
    private val hostileIds = listOf(
        "../escape",
        "../../etc/passwd",
        "a/b/c",
        "..",
        ".",
        "/absolute",
        "trailing/",
        "%2E%2E",
        "with%25percent",
        "nested/../../../deep"
    )

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        root = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}StorageSecurityTest-$stamp-${platform.posix.rand()}"
        )
        storeDir = root.safeAppend("store")
        siblingDir = root.safeAppend("sibling")
        listOf(storeDir, siblingDir).forEach {
            NSFileManager.defaultManager.createDirectoryAtURL(
                it,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
        // A file an escaping write would plausibly aim at.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtURL(root, error = null)
    }

    private fun io() = StorageFileIo(
        isTestMode = true,
        coordinatorTestMode = true,
        coordinationTimeoutMs = 30_000L
    )

    /** Every file that exists under [dir], as absolute paths, recursively. */
    private fun filesUnder(dir: NSURL): List<String> {
        val path = dir.path ?: return emptyList()
        val fm = NSFileManager.defaultManager
        val contents = fm.subpathsAtPath(path) ?: return emptyList()
        return contents.mapNotNull { it as? String }.map { "$path/$it" }
    }

    private fun assertNothingEscaped(where: String) {
        val outside = filesUnder(siblingDir)
        assertTrue(
            outside.isEmpty(),
            "$where wrote outside its own directory: $outside"
        )
        // Every file that was created must sit directly under the store directory, i.e. the
        // encoded id produced exactly one path component and not a nested tree.
        val storePath = storeDir.path!!
        filesUnder(storeDir).forEach { f ->
            val relative = f.removePrefix("$storePath/")
            assertFalse(
                relative.contains('/'),
                "$where created a nested path from an id: '$relative'"
            )
        }
    }

    @Test
    fun chainProgressConfinesHostileChainIdsToItsOwnDirectory() = runTest {
        val store = ChainProgressStore(io(), scope, Json { ignoreUnknownKeys = true }) { storeDir }

        hostileIds.forEach { id ->
            store.saveChainProgress(ChainProgress(chainId = id, totalSteps = 3))
        }
        store.flushNow()

        hostileIds.forEach { id ->
            assertNotNull(
                store.loadChainProgress(id),
                "a hostile id must still round-trip rather than silently vanish: '$id'"
            )
        }
        assertNothingEscaped("ChainProgressStore")

        // Deleting must reach the same file the write created, not a different one.
        hostileIds.forEach { store.deleteChainProgress(it) }
        assertTrue(
            filesUnder(storeDir).isEmpty(),
            "delete must resolve to the same encoded name as the write"
        )
    }

    @Test
    fun taskMetadataConfinesHostileTaskIdsToItsOwnDirectory() = runTest {
        val periodicDir = root.safeAppend("periodic").also {
            NSFileManager.defaultManager.createDirectoryAtURL(
                it, withIntermediateDirectories = true, attributes = null, error = null
            )
        }
        val store = TaskMetadataStore(
            io = io(),
            persistenceJson = Json { ignoreUnknownKeys = true },
            tasksDir = { storeDir },
            periodicDir = { periodicDir }
        )

        hostileIds.forEach { id ->
            store.saveTaskMetadata(id, mapOf("workerClassName" to "W"), periodic = false)
        }

        hostileIds.forEach { id ->
            assertEquals(
                "W",
                store.loadTaskMetadata(id, periodic = false)?.get("workerClassName"),
                "a hostile id must still round-trip: '$id'"
            )
        }
        assertNothingEscaped("TaskMetadataStore")

        // The decoded listing must return the original ids, not their on-disk spelling —
        // otherwise cancelByTag would hand a caller an id it cannot cancel with.
        assertEquals(
            hostileIds.toSet(),
            store.listOneTimeTaskIdsDecoded().toSet(),
            "the decoded listing must invert the encoding exactly"
        )
    }

    @Test
    fun chainDefinitionsAndDeletedMarkersConfineHostileChainIds() = runTest {
        val deletedDir = root.safeAppend("deleted").also {
            NSFileManager.defaultManager.createDirectoryAtURL(
                it, withIntermediateDirectories = true, attributes = null, error = null
            )
        }
        val sharedIo = io()
        val store = ChainDefinitionStore(
            io = sharedIo,
            maintenance = StorageMaintenance(
                io = sharedIo,
                baseDir = { root },
                maintenanceTimestampFile = { root.safeAppend("last_maintenance.txt") },
                diskSpaceBufferBytes = 0L,
                isTestMode = true
            ),
            persistenceJson = Json { ignoreUnknownKeys = true },
            chainsDir = { storeDir },
            deletedChainsDir = { deletedDir },
            maxChainSizeBytes = 10_485_760L,
            deletedMarkerMaxAgeMs = 7L * 24 * 60 * 60 * 1000,
            deleteProgress = { }
        )

        hostileIds.forEach { id ->
            store.saveChainDefinition(id, listOf(listOf(TaskRequest(workerClassName = "W"))))
            store.markChainAsDeleted(id)
        }

        hostileIds.forEach { id ->
            assertTrue(store.chainExists(id), "definition must round-trip for '$id'")
            assertTrue(store.isChainDeleted(id), "marker must round-trip for '$id'")
        }
        assertNothingEscaped("ChainDefinitionStore")

        assertEquals(
            hostileIds.toSet(),
            store.listChainDefinitionIds().toSet(),
            "the definition listing must decode back to the original ids"
        )
    }

    /**
     * Two ids that differ only in characters the encoder escapes must not collide on disk.
     * A collision is a confidentiality bug as much as a correctness one: one task's
     * persisted input would be readable as another's.
     */
    @Test
    fun idsThatDifferOnlyInEscapedCharactersDoNotShareAFile() = runTest {
        val store = TaskMetadataStore(
            io = io(),
            persistenceJson = Json { ignoreUnknownKeys = true },
            tasksDir = { storeDir },
            periodicDir = { siblingDir }
        )

        // "a/b" encodes to "a%2Fb"; the literal text "a%2Fb" encodes to "a%252Fb".
        store.saveTaskMetadata("a/b", mapOf("owner" to "slash"), periodic = false)
        store.saveTaskMetadata("a%2Fb", mapOf("owner" to "literal"), periodic = false)

        assertEquals("slash", store.loadTaskMetadata("a/b", periodic = false)?.get("owner"))
        assertEquals("literal", store.loadTaskMetadata("a%2Fb", periodic = false)?.get("owner"))
    }

    /**
     * The reserved names `.` and `..` must not be usable to address the directory itself.
     * Unescaped, `dir/..json` and friends resolve to entries a caller was never given.
     */
    @Test
    fun reservedNamesAreEncodedRatherThanResolved() = runTest {
        val store = ChainProgressStore(io(), scope, Json { ignoreUnknownKeys = true }) { storeDir }

        store.saveChainProgress(ChainProgress(chainId = ".", totalSteps = 1))
        store.saveChainProgress(ChainProgress(chainId = "..", totalSteps = 2))
        store.flushNow()

        assertEquals(1, store.loadChainProgress(".")?.totalSteps)
        assertEquals(2, store.loadChainProgress("..")?.totalSteps)
        assertEquals(
            2,
            filesUnder(storeDir).size,
            "'.' and '..' must produce two distinct files, not collide or escape"
        )
    }
}
