package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for the Android half of `FileCompressionWorker`, which had none.
 *
 * The README lists `FileCompressionWorker` as Stable and states it "produces a real PKZIP
 * archive (DEFLATE)". Nothing verified that on Android: the `androidMain` actual
 * (`platformCompress`, `compressDirectory`, `compressFile`, `shouldExclude`) was reachable
 * from no test in any source set — the iOS actual has `FileCompressionWorkerIosTest`, the
 * Android one had nothing. These tests read the output back with `java.util.zip.ZipFile`
 * rather than trusting the worker's own success message, so "it produced a valid archive"
 * is asserted rather than assumed.
 */
class FileCompressionWorkerAndroidTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipPath(name: String = "out.zip") = File(temp.root, name).absolutePath

    private fun compress(config: FileCompressionConfig): WorkerResult =
        runBlocking { platformCompress(config) }

    private fun entriesOf(path: String): Map<String, ByteArray> =
        ZipFile(File(path)).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes()
            }
        }

    // ---- Archive correctness --------------------------------------------------------

    /**
     * The load-bearing claim: the output is a real ZIP a standard reader can open, and the
     * bytes that come back out are the bytes that went in.
     */
    @Test
    fun `a compressed file round-trips through a standard zip reader`() {
        val source = temp.newFile("report.txt")
        val content = "the quick brown fox\n".repeat(200)
        source.writeText(content)
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(inputPath = source.absolutePath, outputPath = out)
        )

        assertIs<WorkerResult.Success>(result)
        val entries = entriesOf(out)
        assertEquals(setOf("report.txt"), entries.keys)
        assertEquals(content, entries.getValue("report.txt").decodeToString())
    }

    /** A directory is archived recursively, with paths relative to the directory itself. */
    @Test
    fun `a directory is archived recursively with relative entry paths`() {
        val dir = temp.newFolder("payload")
        File(dir, "top.txt").writeText("top")
        val nested = File(dir, "nested/deeper").apply { mkdirs() }
        File(nested, "leaf.txt").writeText("leaf")
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(inputPath = dir.absolutePath, outputPath = out)
        )

        assertIs<WorkerResult.Success>(result)
        assertEquals(
            setOf("payload/top.txt", "payload/nested/deeper/leaf.txt"),
            entriesOf(out).keys
        )
    }

    /**
     * Every compression level must still produce a readable archive. The levels map to
     * java.util.zip's 3/6/9; what matters to a caller is that none of them corrupts output.
     */
    @Test
    fun `every compression level produces a readable archive`() {
        listOf("low", "medium", "high").forEach { level ->
            val source = temp.newFile("data-$level.txt")
            source.writeText("compressible ".repeat(500))
            val out = zipPath("out-$level.zip")

            val result = compress(
                FileCompressionConfig(
                    inputPath = source.absolutePath,
                    outputPath = out,
                    compressionLevel = level
                )
            )

            assertIs<WorkerResult.Success>(result)
            assertEquals(
                "compressible ".repeat(500),
                entriesOf(out).getValue("data-$level.txt").decodeToString(),
                "level $level must round-trip"
            )
        }
    }

    /**
     * `compressionRatio` is the compressed size as a percentage *of* the original, not the
     * percentage saved. The implementation carries a comment saying this is easy to misread
     * and that the key name is kept for compatibility, so the semantics are pinned here.
     */
    @Test
    fun `compressionRatio reports size relative to the original, not savings`() {
        val source = temp.newFile("repetitive.txt")
        source.writeText("A".repeat(100_000))
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(inputPath = source.absolutePath, outputPath = out)
        )

        assertIs<WorkerResult.Success>(result)
        val ratio = result.data?.get("compressionRatio")?.toString()?.trim('"')?.toIntOrNull()
        assertTrue(ratio != null && ratio in 0..99, "highly compressible input, got ratio=$ratio")
    }

    // ---- Exclude-pattern grammar ----------------------------------------------------

    /**
     * `shouldExclude` implements four pattern forms and they are not regexes. Each is
     * covered because the difference between them is invisible from the config's type
     * (`List<String>`) — a caller only finds out by inspecting the archive.
     */
    @Test
    fun `exclude patterns support extension, suffix, prefix and exact forms`() {
        val dir = temp.newFolder("mixed")
        listOf(
            "keep.txt", "drop.tmp", "notes-backup", "tempfile.dat", "secrets.env"
        ).forEach { File(dir, it).writeText(it) }
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(
                inputPath = dir.absolutePath,
                outputPath = out,
                excludePatterns = listOf("*.tmp", "*backup", "temp*", "secrets.env")
            )
        )

        assertIs<WorkerResult.Success>(result)
        assertEquals(setOf("mixed/keep.txt"), entriesOf(out).keys)
    }

    /** Exclusion is case-insensitive — the implementation passes `ignoreCase` on every form. */
    @Test
    fun `exclude patterns ignore case`() {
        val dir = temp.newFolder("case")
        File(dir, "IMAGE.TMP").writeText("x")
        File(dir, "keep.txt").writeText("y")
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(
                inputPath = dir.absolutePath,
                outputPath = out,
                excludePatterns = listOf("*.tmp")
            )
        )

        assertIs<WorkerResult.Success>(result)
        assertEquals(setOf("case/keep.txt"), entriesOf(out).keys)
    }

    /** An excluded directory takes its whole subtree with it. */
    @Test
    fun `excluding a directory name drops everything under it`() {
        val dir = temp.newFolder("app")
        File(dir, "keep.txt").writeText("keep")
        val cache = File(dir, "cache").apply { mkdirs() }
        File(cache, "blob.bin").writeText("blob")
        val out = zipPath()

        val result = compress(
            FileCompressionConfig(
                inputPath = dir.absolutePath,
                outputPath = out,
                excludePatterns = listOf("cache")
            )
        )

        assertIs<WorkerResult.Success>(result)
        assertEquals(setOf("app/keep.txt"), entriesOf(out).keys)
    }

    // ---- Path safety ----------------------------------------------------------------

    /**
     * Both paths are validated, not just the output. The implementation's own comment
     * records why: `inputPath` feeds `deleteRecursively()` when `deleteOriginal` is set, and
     * a chain step's merged input can supply either string, so validating one and not the
     * other left an unsafe-delete vector.
     */
    @Test
    fun `a traversal input path is rejected before anything is read`() {
        val out = zipPath()
        val result = compress(
            FileCompressionConfig(inputPath = "../../../etc/passwd", outputPath = out)
        )

        val failure = assertIs<WorkerResult.Failure>(result)
        assertContains(failure.message, "Invalid input path")
        assertFalse(File(out).exists(), "no archive should be created")
    }

    @Test
    fun `a traversal output path is rejected before anything is written`() {
        val source = temp.newFile("safe.txt")
        source.writeText("safe")

        val result = compress(
            FileCompressionConfig(
                inputPath = source.absolutePath,
                outputPath = "../../../tmp/evil.zip"
            )
        )

        val failure = assertIs<WorkerResult.Failure>(result)
        assertContains(failure.message, "Invalid output path")
    }

    /**
     * Zip-slip guard. Entry names are what a *consumer* joins onto its extraction
     * directory, so an entry that escapes that directory turns this worker into the
     * delivery mechanism for someone else's path-traversal bug. Names must stay relative
     * and contain no `..` segment.
     */
    @Test
    fun `archive entry names are relative and contain no parent-directory segment`() {
        val dir = temp.newFolder("bundle")
        File(dir, "a.txt").writeText("a")
        File(dir, "sub").apply { mkdirs() }.let { File(it, "b.txt").writeText("b") }
        val out = zipPath()

        assertIs<WorkerResult.Success>(
            compress(FileCompressionConfig(inputPath = dir.absolutePath, outputPath = out))
        )

        entriesOf(out).keys.forEach { name ->
            assertFalse(name.startsWith("/"), "entry must not be absolute: $name")
            assertFalse(name.startsWith("\\"), "entry must not be absolute: $name")
            assertFalse(
                name.split('/').contains(".."),
                "entry must not escape the extraction root: $name"
            )
        }
    }

    // ---- Failure and side-effect paths ----------------------------------------------

    @Test
    fun `a missing input reports failure rather than producing an empty archive`() {
        val missing = File(temp.root, "not-there.txt").absolutePath

        val failure = assertIs<WorkerResult.Failure>(
            compress(FileCompressionConfig(inputPath = missing, outputPath = zipPath()))
        )
        assertContains(failure.message, "does not exist")
    }

    /** The output's parent directory is created rather than the write failing. */
    @Test
    fun `a missing output directory is created`() {
        val source = temp.newFile("input.txt")
        source.writeText("payload")
        val out = File(temp.root, "deeply/nested/out.zip").absolutePath

        assertIs<WorkerResult.Success>(
            compress(FileCompressionConfig(inputPath = source.absolutePath, outputPath = out))
        )
        assertTrue(File(out).exists())
    }

    /**
     * `deleteOriginal` is destructive, so it is pinned in both directions: it deletes when
     * asked, and — the more important half — leaves the input alone when not asked.
     */
    @Test
    fun `deleteOriginal removes the source only when requested`() {
        val kept = temp.newFile("kept.txt").apply { writeText("kept") }
        assertIs<WorkerResult.Success>(
            compress(
                FileCompressionConfig(
                    inputPath = kept.absolutePath,
                    outputPath = zipPath("kept.zip"),
                    deleteOriginal = false
                )
            )
        )
        assertTrue(kept.exists(), "the source must survive when deleteOriginal is false")

        val doomed = temp.newFile("doomed.txt").apply { writeText("doomed") }
        assertIs<WorkerResult.Success>(
            compress(
                FileCompressionConfig(
                    inputPath = doomed.absolutePath,
                    outputPath = zipPath("doomed.zip"),
                    deleteOriginal = true
                )
            )
        )
        assertFalse(doomed.exists(), "the source must be gone when deleteOriginal is true")
    }

    /** An empty directory still yields a valid, empty archive rather than an error. */
    @Test
    fun `an empty directory produces a valid empty archive`() {
        val empty = temp.newFolder("empty")
        val out = zipPath()

        assertIs<WorkerResult.Success>(
            compress(FileCompressionConfig(inputPath = empty.absolutePath, outputPath = out))
        )
        assertTrue(entriesOf(out).isEmpty())
    }
}
