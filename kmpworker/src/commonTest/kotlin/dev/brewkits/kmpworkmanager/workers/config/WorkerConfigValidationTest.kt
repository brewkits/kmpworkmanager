package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validation and serialization contracts for the built-in worker configs.
 *
 * These classes are the library's public input surface: a host serialises one to JSON, hands
 * it to `enqueue(inputJson = …)`, and the worker deserialises it on the other side of a
 * process boundary, possibly days later. Their `init` blocks are therefore the *only*
 * validation between a caller's typo and a worker that fails in the background where nobody
 * is watching — and several of them had no test at all (`HttpSyncConfig`,
 * `IosBackgroundDownloadConfig`, `IosBackgroundUploadConfig` were reachable from no test in
 * any source set).
 *
 * Two properties are pinned for each config:
 *
 * - **`require` rejects at construction**, not at execution. A config that only fails once
 *   the worker runs turns a caller's mistake into a background failure with no stack trace
 *   pointing at the call site.
 * - **A valid config survives a JSON round-trip.** The `init` block runs again on
 *   deserialisation, so a rule that is stricter than what the serialiser can produce would
 *   make a config writable but not readable — the failure would appear on the *next* app
 *   launch, not on the one that wrote it.
 */
class WorkerConfigValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> assertRoundTrips(value: T, encoder: (T) -> String, decoder: (String) -> T) {
        assertEquals(value, decoder(encoder(value)), "config must survive a JSON round-trip")
    }

    // ---- HttpSyncConfig -------------------------------------------------------------

    @Test
    fun httpSyncConfig_acceptsAValidConfigAndRoundTrips() {
        val config = HttpSyncConfig(
            url = "https://api.example.com/sync",
            method = "PUT",
            headers = mapOf("X-Trace" to "abc"),
            timeoutMs = 5_000
        )
        assertEquals(HttpMethod.PUT, config.httpMethod)
        assertRoundTrips(config, { json.encodeToString(HttpSyncConfig.serializer(), it) }) {
            json.decodeFromString(HttpSyncConfig.serializer(), it)
        }
    }

    @Test
    fun httpSyncConfig_rejectsANonHttpScheme() {
        // file:// and custom schemes are the shapes that turn a "sync" into a local file read.
        listOf("file:///etc/passwd", "ftp://example.com", "javascript:alert(1)", "example.com")
            .forEach { url ->
                assertFailsWith<IllegalArgumentException>("scheme must be rejected: $url") {
                    HttpSyncConfig(url = url)
                }
            }
    }

    @Test
    fun httpSyncConfig_rejectsANonPositiveTimeout() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { timeout ->
            assertFailsWith<IllegalArgumentException>("timeout must be rejected: $timeout") {
                HttpSyncConfig(url = "https://example.com", timeoutMs = timeout)
            }
        }
    }

    /**
     * The supported set is GET/POST/PUT/PATCH. DELETE is deliberately absent, and a config
     * that silently accepted it would send a request the worker cannot build.
     */
    @Test
    fun httpSyncConfig_acceptsOnlyTheFourSupportedMethods() {
        listOf("GET", "POST", "PUT", "PATCH", "get", "post").forEach { method ->
            HttpSyncConfig(url = "https://example.com", method = method)
        }
        listOf("DELETE", "HEAD", "OPTIONS", "TRACE", "").forEach { method ->
            assertFailsWith<IllegalArgumentException>("method must be rejected: '$method'") {
                HttpSyncConfig(url = "https://example.com", method = method)
            }
        }
    }

    // ---- IosBackgroundDownloadConfig ------------------------------------------------

    @Test
    fun iosBackgroundDownloadConfig_acceptsAValidConfigAndRoundTrips() {
        val config = IosBackgroundDownloadConfig(
            url = "https://cdn.example.com/asset.bin",
            savePath = "/tmp/asset.bin",
            sessionIdentifier = "com.example.bg",
            isDiscretionary = true,
            allowsCellularAccess = false,
            timeoutMs = 60_000
        )
        assertRoundTrips(config, { json.encodeToString(IosBackgroundDownloadConfig.serializer(), it) }) {
            json.decodeFromString(IosBackgroundDownloadConfig.serializer(), it)
        }
    }

    @Test
    fun iosBackgroundDownloadConfig_rejectsBlankRequiredStrings() {
        assertFailsWith<IllegalArgumentException>("blank savePath must be rejected") {
            IosBackgroundDownloadConfig(url = "https://example.com/a", savePath = "   ")
        }
        assertFailsWith<IllegalArgumentException>("blank sessionIdentifier must be rejected") {
            IosBackgroundDownloadConfig(
                url = "https://example.com/a",
                savePath = "/tmp/a",
                sessionIdentifier = ""
            )
        }
    }

    @Test
    fun iosBackgroundDownloadConfig_rejectsANonHttpSchemeAndNonPositiveTimeout() {
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundDownloadConfig(url = "file:///etc/passwd", savePath = "/tmp/a")
        }
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundDownloadConfig(
                url = "https://example.com/a",
                savePath = "/tmp/a",
                timeoutMs = 0
            )
        }
    }

    /**
     * The session identifier defaults to a library-owned value. Two apps embedding this
     * library must not collide, but the default is also what makes the common case work
     * without configuration — so it is pinned rather than left to drift.
     */
    @Test
    fun iosBackgroundDownloadConfig_hasALibraryOwnedDefaultSessionIdentifier() {
        val config = IosBackgroundDownloadConfig(
            url = "https://example.com/a",
            savePath = "/tmp/a"
        )
        assertTrue(
            config.sessionIdentifier.startsWith("dev.brewkits.kmpworkmanager"),
            "default session id must stay namespaced to this library: ${config.sessionIdentifier}"
        )
        assertTrue(config.allowsCellularAccess, "cellular is allowed unless opted out")
        assertTrue(!config.isDiscretionary, "downloads are not discretionary unless asked")
    }

    // ---- IosBackgroundUploadConfig --------------------------------------------------

    @Test
    fun iosBackgroundUploadConfig_acceptsAValidConfigAndRoundTrips() {
        val config = IosBackgroundUploadConfig(
            url = "https://api.example.com/upload",
            filePath = "/tmp/payload.bin",
            httpMethod = "PUT"
        )
        assertRoundTrips(config, { json.encodeToString(IosBackgroundUploadConfig.serializer(), it) }) {
            json.decodeFromString(IosBackgroundUploadConfig.serializer(), it)
        }
    }

    @Test
    fun iosBackgroundUploadConfig_rejectsBlankRequiredStrings() {
        assertFailsWith<IllegalArgumentException>("blank filePath must be rejected") {
            IosBackgroundUploadConfig(url = "https://example.com/u", filePath = " ")
        }
        assertFailsWith<IllegalArgumentException>("blank httpMethod must be rejected") {
            IosBackgroundUploadConfig(
                url = "https://example.com/u",
                filePath = "/tmp/a",
                httpMethod = ""
            )
        }
        assertFailsWith<IllegalArgumentException>("blank sessionIdentifier must be rejected") {
            IosBackgroundUploadConfig(
                url = "https://example.com/u",
                filePath = "/tmp/a",
                sessionIdentifier = ""
            )
        }
    }

    @Test
    fun iosBackgroundUploadConfig_rejectsANonHttpSchemeAndNonPositiveTimeout() {
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundUploadConfig(url = "ftp://example.com/u", filePath = "/tmp/a")
        }
        assertFailsWith<IllegalArgumentException> {
            IosBackgroundUploadConfig(
                url = "https://example.com/u",
                filePath = "/tmp/a",
                timeoutMs = -5
            )
        }
    }

    // ---- FileCompressionConfig ------------------------------------------------------

    /**
     * The `.zip` suffix requirement is the one rule here that is about the *output* rather
     * than the input, and it is easy to regress into a warning: without it the worker writes
     * a PKZIP archive under whatever name it was given, and the host has a file its own
     * tooling will not open.
     */
    @Test
    fun fileCompressionConfig_requiresAZipOutputExtension() {
        FileCompressionConfig(inputPath = "/tmp/in", outputPath = "/tmp/out.zip")
        FileCompressionConfig(inputPath = "/tmp/in", outputPath = "/tmp/out.ZIP")

        listOf("/tmp/out", "/tmp/out.tar", "/tmp/out.zip.gz").forEach { path ->
            assertFailsWith<IllegalArgumentException>("must reject output '$path'") {
                FileCompressionConfig(inputPath = "/tmp/in", outputPath = path)
            }
        }
    }

    @Test
    fun fileCompressionConfig_rejectsBlankPathsAndUnknownLevels() {
        assertFailsWith<IllegalArgumentException> {
            FileCompressionConfig(inputPath = "  ", outputPath = "/tmp/out.zip")
        }
        assertFailsWith<IllegalArgumentException> {
            FileCompressionConfig(inputPath = "/tmp/in", outputPath = "   ")
        }
        assertFailsWith<IllegalArgumentException>("an unknown level must fail eagerly") {
            FileCompressionConfig(
                inputPath = "/tmp/in",
                outputPath = "/tmp/out.zip",
                compressionLevel = "ludicrous"
            )
        }
    }

    /** Level parsing is case-insensitive and maps to the three documented values. */
    @Test
    fun fileCompressionConfig_parsesEveryDocumentedLevel() {
        assertEquals(
            CompressionLevel.LOW,
            FileCompressionConfig("/tmp/in", "/tmp/o.zip", compressionLevel = "LOW").level
        )
        assertEquals(
            CompressionLevel.MEDIUM,
            FileCompressionConfig("/tmp/in", "/tmp/o.zip", compressionLevel = "medium").level
        )
        assertEquals(
            CompressionLevel.HIGH,
            FileCompressionConfig("/tmp/in", "/tmp/o.zip", compressionLevel = "High").level
        )
    }
}
