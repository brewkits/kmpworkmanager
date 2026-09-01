package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression net for the v2.5.0 QA-review-found overflow-file leak, and for the 3.3.0
 * multi-process fix (ROADMAP.md "File-backed `OverflowFileRegistry` for Android
 * multi-process apps").
 *
 * **The original bug**: `NativeTaskScheduler.cancel(id)` did not delete the
 * `cacheDir/kmp_input_<uuid>.json` overflow file that was created when the input JSON
 * exceeded 8 KB. The file lived in cacheDir until the 24 h `cleanupStaleAlarms` sweep
 * mopped it up.
 *
 * **The v2.5.0 fix**: an `OverflowFileRegistry` records the (taskId, absolutePath)
 * mapping at schedule time, backed by `SharedPreferences`. `cancel(id)` looks the
 * mapping up, deletes the file, and removes the entry.
 *
 * **The 3.3.0 fix**: `SharedPreferences` caches its contents in memory per `Context`
 * instance. Hosts with separate processes (`:background`, `:push`, …) each hold their
 * own cache, so a `register()` in process A could race a `consumeAndDelete()` in
 * process B — B's cache doesn't see A's write yet, returns null, and the file leaks
 * until the janitor sweep. The registry is now backed by one file per entry under
 * `cacheDir/overflow_registry/`, which has no per-process caching layer to race.
 *
 * **Why Robolectric**: both the legacy `SharedPreferences` path (migration test) and
 * `cacheDir` need a real Android `Context`. Robolectric provides fast in-memory/real-fs
 * implementations suitable for JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverflowFileRegistryTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    private val legacyPrefs
        get() = context.getSharedPreferences("dev.brewkits.kmpworkmanager.overflow_files", Context.MODE_PRIVATE)

    private val registryDir
        get() = File(context.cacheDir, "overflow_registry")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "registry-test-${System.nanoTime()}").apply { mkdirs() }
        legacyPrefs.edit().clear().commit()
        registryDir.deleteRecursively()
        OverflowFileRegistry.resetMigrationStateForTesting()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        legacyPrefs.edit().clear().commit()
        registryDir.deleteRecursively()
    }

    private fun makeOverflowFile(name: String, content: String = "{\"x\":1}"): File =
        File(tempDir, name).apply { writeText(content) }

    @Test
    fun registerThenConsume_deletesFile_andReturnsPath() {
        val file = makeOverflowFile("kmp_input_a.json")
        assertTrue(file.exists(), "test setup: file must exist")

        OverflowFileRegistry.register(context, "task-A", file.absolutePath)
        val returned = OverflowFileRegistry.consumeAndDelete(context, "task-A")

        assertEquals(file.absolutePath, returned, "consume must return registered path")
        assertFalse(file.exists(), "file must be deleted from disk after consume")
    }

    @Test
    fun consume_isIdempotent() {
        val file = makeOverflowFile("kmp_input_b.json")
        OverflowFileRegistry.register(context, "task-B", file.absolutePath)

        // First call removes both the file and the entry.
        OverflowFileRegistry.consumeAndDelete(context, "task-B")
        // Second call must NOT throw, and must return null (no entry).
        val second = OverflowFileRegistry.consumeAndDelete(context, "task-B")
        assertNull(second, "second consume must return null without throwing")
    }

    @Test
    fun register_nullPath_isNoop() {
        // Callers in `buildWorkData` use a single code path that doesn't always have an
        // overflow file. Letting `register(..., null)` silently skip lets the caller stay
        // ternary-free.
        OverflowFileRegistry.register(context, "task-C", null)
        val returned = OverflowFileRegistry.consumeAndDelete(context, "task-C")
        assertNull(returned, "null-path register must not produce a consumable entry")
    }

    @Test
    fun consume_missingTaskId_returnsNull() {
        // No register call for "task-D" — consume must return null cleanly.
        val returned = OverflowFileRegistry.consumeAndDelete(context, "task-D")
        assertNull(returned)
    }

    @Test
    fun consume_whenFileAlreadyDeletedExternally_stillRemovesEntry() {
        // Worker finished + deleted the file in its own finally block, then user cancelled.
        // Consume should not throw, should clean up the bookkeeping, return the recorded
        // path so the caller can log appropriately.
        val file = makeOverflowFile("kmp_input_e.json")
        OverflowFileRegistry.register(context, "task-E", file.absolutePath)
        file.delete()
        assertFalse(file.exists())

        val returned = OverflowFileRegistry.consumeAndDelete(context, "task-E")
        assertEquals(file.absolutePath, returned)

        // Entry should be gone — a subsequent consume returns null.
        assertNull(OverflowFileRegistry.consumeAndDelete(context, "task-E"))
    }

    @Test
    fun manyRegisterCancelCycles_leakNothing() {
        // Stress: 100 round-trips. After the loop, the registry directory should be empty
        // and tempDir should be empty. Pinned to prove the spec for camera-app
        // "save → cancel" workloads doesn't accumulate residue.
        repeat(100) { i ->
            val f = makeOverflowFile("kmp_input_loop_$i.json")
            OverflowFileRegistry.register(context, "task-loop-$i", f.absolutePath)
            OverflowFileRegistry.consumeAndDelete(context, "task-loop-$i")
        }

        val leftoverEntries = registryDir.listFiles()?.toList() ?: emptyList()
        assertTrue(leftoverEntries.isEmpty(), "registry dir must be empty after 100 round-trips, got ${leftoverEntries.map { it.name }}")

        val leftoverFiles = tempDir.listFiles()?.filter { it.name.startsWith("kmp_input_loop_") } ?: emptyList()
        assertTrue(leftoverFiles.isEmpty(), "no overflow files should remain, got ${leftoverFiles.map { it.name }}")
    }

    // ── 3.3.0: file-backed storage properties ──────────────────────────────────────────

    @Test
    fun distinctTaskIds_neverCollide_evenWithSimilarLookingIds() {
        // The encoding must be INJECTIVE — a hash-based key derivation risks two
        // different task ids mapping to the same filename, which would silently merge
        // (and mutually clobber/delete) two unrelated tasks' overflow-file entries. This
        // is the correctness property a hash-based scheme (like a CRC32 key) would NOT
        // guarantee; percent-encoding does, by construction.
        val fileA = makeOverflowFile("kmp_input_f.json")
        val fileB = makeOverflowFile("kmp_input_g.json")

        OverflowFileRegistry.register(context, "task/with/slashes", fileA.absolutePath)
        OverflowFileRegistry.register(context, "task-with-slashes", fileB.absolutePath)

        // Consuming one must not affect the other.
        val consumedA = OverflowFileRegistry.consumeAndDelete(context, "task/with/slashes")
        assertEquals(fileA.absolutePath, consumedA)
        assertTrue(fileB.exists(), "an unrelated similarly-named task's file must survive")

        val consumedB = OverflowFileRegistry.consumeAndDelete(context, "task-with-slashes")
        assertEquals(fileB.absolutePath, consumedB)
    }

    @Test
    fun hostileTaskIds_cannotEscapeTheRegistryDirectory() {
        // Task ids are caller-supplied (BackgroundTaskScheduler.enqueue(id: String, ...)),
        // so a path-traversal-shaped id must not let register() write outside
        // overflow_registry/, and must still round-trip correctly through the public API.
        val file = makeOverflowFile("kmp_input_h.json")
        val hostileIds = listOf(
            "../../../etc/passwd",
            "..",
            ".",
            "a/../../b",
            "task with spaces",
            "τάσκ-unicode-🎯"
        )

        for (id in hostileIds) {
            OverflowFileRegistry.register(context, id, file.absolutePath)
        }

        // Every entry must have landed inside overflow_registry/ — none of the hostile
        // ids may have escaped it (e.g. by writing into cacheDir directly or its parent).
        val entries = registryDir.listFiles()?.toList() ?: emptyList()
        assertEquals(hostileIds.size, entries.size, "every hostile id must produce exactly one contained entry file")
        for (entry in entries) {
            assertEquals(registryDir.canonicalPath, entry.canonicalFile.parentFile?.canonicalPath)
        }

        // And each must still be independently consumable via the public API.
        for (id in hostileIds) {
            assertEquals(file.absolutePath, OverflowFileRegistry.consumeAndDelete(context, id))
        }
    }

    // ── 3.3.0: legacy SharedPreferences migration ───────────────────────────────────────

    @Test
    fun legacyPrefsEntry_isMigratedAndConsumedCorrectly() {
        // Simulate a v2.5.0-era app that has an existing registry entry in
        // SharedPreferences from before the upgrade to the file-backed registry — written
        // directly to prefs, bypassing OverflowFileRegistry entirely (it never wrote
        // prefs in this test process before this point).
        val file = makeOverflowFile("kmp_input_legacy.json")
        legacyPrefs.edit().putString("of_legacy-task", file.absolutePath).commit()

        // No register() call for "legacy-task" in this process — the only way consume can
        // find it is by migrating the legacy entry first.
        val returned = OverflowFileRegistry.consumeAndDelete(context, "legacy-task")

        assertEquals(file.absolutePath, returned, "legacy entry must be found via migration")
        assertFalse(file.exists(), "migrated entry must still delete the overflow file")
        assertTrue(legacyPrefs.all.isEmpty(), "legacy prefs must be cleared after migration")
    }

    @Test
    fun legacyMigration_isIdempotent_acrossRepeatedCalls() {
        val fileX = makeOverflowFile("kmp_input_x.json")
        val fileY = makeOverflowFile("kmp_input_y.json")
        legacyPrefs.edit()
            .putString("of_legacy-x", fileX.absolutePath)
            .putString("of_legacy-y", fileY.absolutePath)
            .commit()

        // Trigger migration via a register() call unrelated to either legacy entry.
        val untouched = makeOverflowFile("kmp_input_z.json")
        OverflowFileRegistry.register(context, "task-z", untouched.absolutePath)

        // Simulate migration running again (e.g. a second process, or the latch being
        // re-armed) — must not throw, must not duplicate/corrupt entries.
        OverflowFileRegistry.resetMigrationStateForTesting()
        OverflowFileRegistry.register(context, "task-z2", untouched.absolutePath)

        assertEquals(fileX.absolutePath, OverflowFileRegistry.consumeAndDelete(context, "legacy-x"))
        assertEquals(fileY.absolutePath, OverflowFileRegistry.consumeAndDelete(context, "legacy-y"))
    }
}
