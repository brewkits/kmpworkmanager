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
 * Regression net for the v2.5.0 QA-review-found overflow-file leak.
 *
 * **The bug**: `NativeTaskScheduler.cancel(id)` did not delete the
 * `cacheDir/kmp_input_<uuid>.json` overflow file that was created when the input JSON
 * exceeded 8 KB. The file lived in cacheDir until the 24 h `cleanupStaleAlarms` sweep
 * mopped it up. Apps that schedule and cancel many large-input tasks (camera draft
 * save → cancel → save → cancel → …) accumulated megabytes of orphaned files.
 *
 * **The fix**: an `OverflowFileRegistry` records the (taskId, absolutePath) mapping
 * at schedule time. `cancel(id)` looks the mapping up, deletes the file, and removes
 * the entry. This test pins:
 *
 *  1. `register` writes a mapping that survives a fresh registry lookup.
 *  2. `consumeAndDelete` returns the recorded path AND removes the file.
 *  3. `consumeAndDelete` is idempotent — a second call returns null without throwing.
 *  4. `register(path = null)` is a no-op (lets callers pass through without a guard).
 *  5. The on-disk file is actually gone after consume (not just the prefs entry).
 *
 * **Why Robolectric**: SharedPreferences requires a real Android Context. Robolectric
 * provides a fast in-memory implementation suitable for JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverflowFileRegistryTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "registry-test-${System.nanoTime()}").apply { mkdirs() }
        // Start each test with a clean prefs slate.
        context.getSharedPreferences("dev.brewkits.kmpworkmanager.overflow_files", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        context.getSharedPreferences("dev.brewkits.kmpworkmanager.overflow_files", Context.MODE_PRIVATE)
            .edit().clear().commit()
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
        // Stress: 100 round-trips. After the loop, prefs should be empty and tempDir
        // should be empty. Pinned to prove the spec for camera-app "save → cancel"
        // workloads doesn't accumulate residue.
        repeat(100) { i ->
            val f = makeOverflowFile("kmp_input_loop_$i.json")
            OverflowFileRegistry.register(context, "task-loop-$i", f.absolutePath)
            OverflowFileRegistry.consumeAndDelete(context, "task-loop-$i")
        }

        val prefs = context.getSharedPreferences("dev.brewkits.kmpworkmanager.overflow_files", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty(), "prefs must be empty after 100 round-trips, got ${prefs.all}")

        val leftover = tempDir.listFiles()?.filter { it.name.startsWith("kmp_input_loop_") } ?: emptyList()
        assertTrue(leftover.isEmpty(), "no overflow files should remain, got ${leftover.map { it.name }}")
    }
}
