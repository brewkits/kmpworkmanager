@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression net for a review finding: task/chain ids are caller-supplied
 * (`scheduler.enqueue(id, ...)`, `TaskChain.withId(...)`) and were interpolated directly
 * into filenames across 13 call sites in `IosFileStorage` with no sanitization —
 * `dir.safeAppend("$id.json")`. `safeAppend` only guards against
 * `URLByAppendingPathComponent` returning null (NPE-safety); it has no traversal
 * awareness. Fixed via `String.encodeAsPathComponent()`.
 *
 * Two layers of coverage:
 * - Pure unit tests of `encodeAsPathComponent()` — fast, deterministic, and the right
 *   place to pin the injectivity/backward-compatibility properties precisely.
 * - An integration test through the real `IosFileStorage` API, because the property
 *   that actually matters in production — whether a hostile id can make
 *   `URLByAppendingPathComponent`/`NSFileManager` write outside the intended directory —
 *   depends on Foundation's own path-resolution behavior, not just this function's
 *   output string.
 */
class V331PathTraversalTest {

    // ── Pure unit tests of the encoder ──────────────────────────────────────────

    @Test
    fun `ordinary ids pass through unchanged`() {
        // Backward compatibility is the whole point: an app already running with
        // task ids like these must resolve to the exact same on-disk filename after
        // upgrading, or metadata for already-scheduled tasks silently "disappears".
        listOf(
            "nightly-sync",
            "upload_1",
            "com.example.SyncTask",
            "task.name.v2",
            "A1B2C3D4-E5F6-7890-ABCD-EF1234567890",
            "任务同步"
        ).forEach { id ->
            assertEquals(id, id.encodeAsPathComponent(), "safe id must round-trip unchanged: '$id'")
        }
    }

    @Test
    fun `a slash is percent-encoded`() {
        assertEquals("a%2Fb", "a/b".encodeAsPathComponent())
        assertEquals("%2Fetc%2Fpasswd", "/etc/passwd".encodeAsPathComponent())
    }

    @Test
    fun `traversal-shaped ids are percent-encoded not left able to add path segments`() {
        val encoded = "../../../etc/passwd".encodeAsPathComponent()
        assertTrue('/' !in encoded, "encoded output must never contain a literal '/': $encoded")
    }

    @Test
    fun `bare dot and double-dot are escaped as the reserved filesystem names they are`() {
        assertEquals("%2E", ".".encodeAsPathComponent())
        assertEquals("%2E%2E", "..".encodeAsPathComponent())
        // A dot WITHIN a longer id is not a reserved name and must not be touched —
        // this is what keeps "com.example.SyncTask" round-tripping above.
        assertEquals("a.b", "a.b".encodeAsPathComponent())
    }

    @Test
    fun `encoding is injective a literal percent-slash cannot collide with a real slash`() {
        // Without escaping '%' first, the id "a%2Fb" (someone's literal string) and the id
        // "a/b" (an actual slash) would both encode to "a%2Fb" -- two different ids
        // silently sharing one file. Escaping '%' before '/' rules this out.
        val real = "a/b".encodeAsPathComponent()
        val literal = "a%2Fb".encodeAsPathComponent()
        assertNotEquals(real, literal, "a real '/' and the literal text '%2F' must not collide")
    }

    @Test
    fun `distinct hostile ids encode to distinct filenames`() {
        val ids = listOf(
            "../../../etc/passwd", "..", ".", "a/../../b",
            "/etc/passwd", "a/b", "b/a", "%2Fetc%2Fpasswd"
        )
        val encoded = ids.map { it.encodeAsPathComponent() }
        assertEquals(ids.size, encoded.toSet().size, "no two distinct hostile ids may collide: $encoded")
    }

    // ── Integration: the real IosFileStorage API ────────────────────────────────

    private lateinit var storage: IosFileStorage
    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() {
        val name = "V331PathTraversalTest-${(NSDate().timeIntervalSince1970 * 1000).toLong()}-${platform.posix.rand()}"
        testDirectory = NSURL.fileURLWithPath("${NSTemporaryDirectory()}$name")
        NSFileManager.defaultManager.createDirectoryAtURL(testDirectory, withIntermediateDirectories = true, attributes = null, error = null)
        storage = IosFileStorage(baseDirectory = testDirectory)
    }

    @AfterTest
    fun tearDown() = runTest {
        storage.close()
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    @Test
    fun `task metadata round-trips for a hostile id without escaping the storage directory`() = runTest {
        val hostileId = "../../../etc/passwd"
        val metadata = mapOf("workerClassName" to "SyncWorker", "inputJson" to "null")

        storage.saveTaskMetadata(hostileId, metadata, periodic = false)

        val tasksDir = testDirectory.URLByAppendingPathComponent("metadata")!!.URLByAppendingPathComponent("tasks")!!
        val entries = NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(tasksDir.path!!, error = null)
            ?: emptyList<Any?>()
        assertEquals(1, entries.size, "exactly one file must be written, inside metadata/tasks/, got: $entries")

        val loaded = storage.loadTaskMetadata(hostileId, periodic = false)
        assertEquals(metadata, loaded, "the hostile id must still round-trip correctly through the public API")
    }

    @Test
    fun `two hostile ids that differ only by path shape do not clobber each other`() = runTest {
        val idA = "a/b"
        val idB = "b/a"

        storage.saveTaskMetadata(idA, mapOf("workerClassName" to "WorkerA"), periodic = false)
        storage.saveTaskMetadata(idB, mapOf("workerClassName" to "WorkerB"), periodic = false)

        assertEquals(mapOf("workerClassName" to "WorkerA"), storage.loadTaskMetadata(idA, periodic = false))
        assertEquals(mapOf("workerClassName" to "WorkerB"), storage.loadTaskMetadata(idB, periodic = false))
    }

    @Test
    fun `chain definition survives a hostile chainId`() = runTest {
        val hostileChainId = "../../evil-chain"
        storage.saveChainDefinition(hostileChainId, listOf(listOf(TaskRequest("SyncWorker"))))

        val chainsDir = testDirectory.URLByAppendingPathComponent("chains")!!
        val entries = NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(chainsDir.path!!, error = null)
            ?: emptyList<Any?>()
        assertEquals(1, entries.size, "exactly one chain definition file must exist, inside chains/, got: $entries")

        val loaded = storage.loadChainDefinition(hostileChainId)
        assertEquals(1, loaded?.size, "the hostile chainId must still round-trip correctly")
    }

    @Test
    fun `ordinary ids still produce the expected literal filename on disk`() {
        // Backward-compat, proven on disk rather than just at the string level: an
        // ordinary id must land at exactly the filename it always did.
        runTest { storage.saveTaskMetadata("nightly-sync", mapOf("k" to "v"), periodic = false) }

        val expectedFile = testDirectory
            .URLByAppendingPathComponent("metadata")!!
            .URLByAppendingPathComponent("tasks")!!
            .URLByAppendingPathComponent("nightly-sync.json")!!
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(expectedFile.path!!),
            "an ordinary id must still produce the literal 'nightly-sync.json' filename"
        )
    }
}
