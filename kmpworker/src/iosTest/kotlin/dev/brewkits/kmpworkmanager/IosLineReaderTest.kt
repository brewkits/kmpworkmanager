@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.persistence.streamLinesFromPath
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import kotlin.test.*

/**
 * Unit tests for [streamLinesFromPath] — the Okio-based line reader introduced
 * to replace NSString.stringWithContentsOfFile (which loaded the whole file into RAM).
 *
 * Every test uses a distinct file name to avoid shared-state pollution across runs.
 *
 * Invariant under test:
 *   streamLinesFromPath emits every non-blank line in file order, never more, never fewer,
 *   regardless of file size or whether a trailing newline is present.
 */
class IosLineReaderTest {

    private fun writeTempFile(name: String, content: String): String {
        val path = NSTemporaryDirectory() + "kmptest_linereader_$name.jsonl"
        (content as NSString).writeToFile(path, atomically = true,
            encoding = NSUTF8StringEncoding, error = null)
        return path
    }

    private fun collectLines(path: String): List<String> {
        val lines = mutableListOf<String>()
        streamLinesFromPath(path) { lines.add(it) }
        return lines
    }

    // ─── Boundary: empty / missing ────────────────────────────────────────────

    @Test
    fun `empty file yields no lines`() {
        val path = writeTempFile("empty", "")
        assertEquals(emptyList(), collectLines(path))
    }

    @Test
    fun `non-existent path yields no lines and does not throw`() {
        assertEquals(emptyList(), collectLines("/tmp/kmptest_linereader_does_not_exist.jsonl"))
    }

    // ─── Trailing-newline behaviour ───────────────────────────────────────────

    @Test
    fun `single line without trailing newline is emitted`() {
        val path = writeTempFile("no-trail", """{"id":1}""")
        val lines = collectLines(path)
        assertEquals(1, lines.size)
        assertEquals("""{"id":1}""", lines[0])
    }

    @Test
    fun `single line with trailing newline is emitted exactly once`() {
        val path = writeTempFile("trail", """{"id":1}""" + "\n")
        assertEquals(1, collectLines(path).size)
    }

    // ─── Line order and content ───────────────────────────────────────────────

    @Test
    fun `multiple lines emitted in file order`() {
        val content = (1..5).joinToString("\n") { """{"id":$it}""" } + "\n"
        val path = writeTempFile("order", content)
        val lines = collectLines(path)
        assertEquals(5, lines.size)
        assertEquals("""{"id":1}""", lines.first())
        assertEquals("""{"id":5}""", lines.last())
    }

    @Test
    fun `blank lines are skipped`() {
        val content = """{"a":"1"}""" + "\n\n\n" + """{"a":"2"}""" + "\n"
        val path = writeTempFile("blanks", content)
        assertEquals(2, collectLines(path).size)
    }

    // ─── Scale: verify correct count for large files ──────────────────────────

    @Test
    fun `10000-line file is streamed accurately`() {
        val lineCount = 10_000
        val lineContent = "This is a test line with some content"
        val content = (1..lineCount).joinToString("\n") { lineContent } + "\n"
        val path = writeTempFile("large_scale", content)
        var counted = 0
        streamLinesFromPath(path) { line -> if (line == lineContent) counted++ }
        assertEquals(lineCount, counted, "Streaming must accurately process every line in a large file")
    }

    @Test
    fun `1000-line file content round-trips correctly`() {
        val lineCount = 1000
        val expected = (1..lineCount).map { """{"id":$it}""" }
        val content = expected.joinToString("\n") + "\n"
        val path = writeTempFile("roundtrip", content)
        val actual = collectLines(path)
        assertEquals(expected, actual, "Line content must survive the streaming round-trip")
    }

    // ─── Security: verify UTF-8 boundary safety ──────────────────────────────

    @Test
    fun `multi-byte character at 8KB chunk boundary is not corrupted`() {
        // 8190 ASCII bytes + 4-byte emoji straddles the default Okio read buffer boundary
        val padding = "a".repeat(8190)
        val emoji = "🚀"
        val content = padding + emoji + "\n"
        val path = writeTempFile("utf8_boundary", content)
        var result = ""
        streamLinesFromPath(path) { line -> result = line }
        assertTrue(result.endsWith(emoji), "Emoji at chunk boundary must not be corrupted. Got: ${result.takeLast(5)}")
    }
}
