package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker
import dev.brewkits.kmpworkmanager.workers.config.ChecksumAlgorithm
import dev.brewkits.kmpworkmanager.workers.config.HttpDownloadConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpRequestConfig
import dev.brewkits.kmpworkmanager.workers.config.HttpUploadConfig
import dev.brewkits.kmpworkmanager.workers.utils.HttpWorkerJson
import dev.brewkits.kmpworkmanager.workers.utils.installSecureRedirectFollowing
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the HTTP-worker fixes shipped in v3.5.0.
 *
 * Per the project convention (see CLAUDE.md — "Testing Conventions"), this file is tied to
 * one release and is never edited afterwards.
 */
class V350HttpBugFixesTest {

    /**
     * Fails the test if any request reaches the network — every case here must be rejected
     * during validation, before a request is ever issued.
     */
    private fun clientThatMustNotBeCalled() = HttpClient(
        MockEngine { request ->
            throw AssertionError(
                "request must never be issued for a rejected file path, but got ${request.url}",
            )
        },
    ) { install(HttpTimeout) }

    private fun configJson(filePath: String) = HttpWorkerJson.encodeToString(
        HttpUploadConfig(url = "https://example.com/upload", filePath = filePath),
    )

    // ── HttpUploadWorker validated the URL but never the file path ─────────────────────
    //
    // It was the only HTTP worker missing `SecurityValidator.validateFilePath` —
    // ParallelHttpUploadWorker, HttpDownloadWorker (savePath) and IosBackgroundUploadWorker
    // all validate theirs. That mattered because `filePath` is not necessarily
    // author-controlled: in a chain step with `mergeOutputFromPreviousStep = true` the
    // PREVIOUS step's output wins on a key collision, so an earlier, less-trusted step could
    // point this upload at an arbitrary local file. Same vector v3.4.0 closed for
    // FileCompressionWorker.inputPath.

    @Test
    fun upload_rejectsTraversalPath_withoutIssuingRequest() = runTest {
        val result = HttpUploadWorker(clientThatMustNotBeCalled())
            .doWork(configJson("/tmp/uploads/../../../etc/passwd"), WorkerEnvironment())

        val failure = assertIs<WorkerResult.Failure>(result)
        assertTrue(
            failure.message.contains("file path", ignoreCase = true),
            "expected a file-path rejection, got: ${failure.message}",
        )
    }

    @Test
    fun upload_rejectsSensitiveRoots_withoutIssuingRequest() = runTest {
        // The roots SecurityValidator.validateFilePath treats as sensitive.
        listOf(
            "/etc/hosts",
            "/proc/self/environ",
            "/sys/class/net/wlan0/address",
            "/dev/urandom",
            "/private/etc/passwd",
        ).forEach { path ->
            val result = HttpUploadWorker(clientThatMustNotBeCalled())
                .doWork(configJson(path), WorkerEnvironment())

            val failure = assertIs<WorkerResult.Failure>(result)
            assertTrue(
                failure.message.contains("file path", ignoreCase = true),
                "expected '$path' to be rejected as an unsafe file path, got: ${failure.message}",
            )
        }
    }

    @Test
    fun upload_rejectsEncodedTraversal_withoutIssuingRequest() = runTest {
        // validateFilePath case-folds and decodes before checking, so the encoded forms must
        // be rejected too — otherwise the check is trivially bypassable.
        listOf(
            "/tmp/%2e%2e/%2e%2e/etc/passwd",
            "/tmp/%2E%2E/secrets",
            "/tmp/..\\..\\windows\\system32",
        ).forEach { path ->
            val result = HttpUploadWorker(clientThatMustNotBeCalled())
                .doWork(configJson(path), WorkerEnvironment())

            assertIs<WorkerResult.Failure>(
                result,
                "expected encoded traversal '$path' to be rejected",
            )
        }
    }

    @Test
    fun upload_pathRejection_isPermanent_notRetryable() = runTest {
        // An unsafe path is a caller/config error, not a transient condition: retrying cannot
        // make it safe, and a retry loop would keep re-attempting an exfiltration.
        val result = HttpUploadWorker(clientThatMustNotBeCalled())
            .doWork(configJson("/etc/passwd"), WorkerEnvironment())

        val failure = assertIs<WorkerResult.Failure>(result)
        assertFalse(
            failure.shouldRetry,
            "a rejected file path must not be retried — it can never become valid",
        )
    }

    // ── #6 — the redirect interceptor never advanced its notion of "current request" ────
    //
    // `request` was captured once and reused for every hop, which broke two things at
    // once on hop >= 2: a relative `Location` was resolved against the ORIGINAL host, and
    // `takeFrom(request)` re-copied the original `Authorization`/`Cookie` headers, undoing
    // a strip that an earlier cross-origin hop had performed. A one-hop test cannot see
    // either — both cases below need three requests.

    private fun redirectClient(engine: MockEngine) =
        HttpClient(engine) { install(HttpTimeout) }.installSecureRedirectFollowing()

    @Test
    fun relativeLocationOnSecondHop_resolvesAgainstCurrentHost_notTheOriginal() = runTest {
        val hosts = mutableListOf<String>()
        val paths = mutableListOf<String>()
        var hop = 0
        val engine = MockEngine { request ->
            hop++
            hosts.add(request.url.host)
            paths.add(request.url.encodedPath)
            when (hop) {
                1 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://third-party.example.com/next"),
                )
                // Relative Location — must resolve against third-party, the host that sent it.
                2 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "/admin/secret"),
                )
                else -> respond(content = "ok", status = HttpStatusCode.OK)
            }
        }

        redirectClient(engine).get("https://original.example.com/start")

        assertEquals(3, hop)
        assertEquals(
            "third-party.example.com",
            hosts[2],
            "a relative Location must resolve against the host that issued it — resolving " +
                "against the original host lets a third-party redirect aim the request at " +
                "any path on the origin",
        )
        assertEquals("/admin/secret", paths[2])
    }

    @Test
    fun credentialsStrippedCrossOrigin_areNotRestoredWhenTheChainBouncesBack() = runTest {
        val authHeaders = mutableListOf<String?>()
        var hop = 0
        val engine = MockEngine { request ->
            hop++
            authHeaders.add(request.headers[HttpHeaders.Authorization])
            when (hop) {
                1 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://third-party.example.com/next"),
                )
                // Back to the original host. Comparing against the ORIGINAL request made this
                // hop look same-origin, so the credentials stripped on hop 2 came back.
                2 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://original.example.com/final"),
                )
                else -> respond(content = "ok", status = HttpStatusCode.OK)
            }
        }

        redirectClient(engine).get("https://original.example.com/start") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
        }

        assertEquals(3, hop)
        assertEquals("Bearer secret-token", authHeaders[0], "the first hop is same-origin")
        assertNull(authHeaders[1], "Authorization must not leak to the third party")
        assertNull(
            authHeaders[2],
            "once dropped on a cross-origin hop, credentials must stay dropped — a redirect " +
                "chain that has left the origin must not regain them by pointing back at it",
        )
    }

    // ── #7 — HTTP 416 published the partial without verifying its checksum ─────────────
    //
    // The 416 branch called finalizePartial() and returned Success directly, skipping the
    // expectedChecksum block further down. That is the worst possible place to skip it:
    // the server has just said it will not send those bytes again, so nothing downstream
    // would ever check a partial left behind by a truncated earlier attempt.

    // SHA-256("hello world")
    private val helloWorldSha256 =
        "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

    private fun downloadConfig(savePath: String, expected: String?) = HttpWorkerJson.encodeToString(
        HttpDownloadConfig(
            url = "https://example.com/file",
            savePath = savePath,
            resumable = true,
            expectedChecksum = expected,
            checksumAlgorithm = ChecksumAlgorithm.SHA256,
        ),
    )

    @Test
    fun http416_withChecksumMismatch_fails_andDoesNotPublishThePartial() = runTest {
        val fs = FileSystem.SYSTEM
        val savePath = "v350_416_bad_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "$savePath.partial".toPath()
        fs.write(partialPath) { writeUtf8("corrupted") }

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.RequestedRangeNotSatisfiable) }
        try {
            val result = HttpDownloadWorker(HttpClient(engine) { install(HttpTimeout) }, fs)
                .doWork(downloadConfig(savePath.toString(), helloWorldSha256), WorkerEnvironment(null) { false })

            assertIs<WorkerResult.Failure>(result, "a 416 over a corrupted partial must not succeed")
            assertTrue(result.message.contains("mismatch", ignoreCase = true), result.message)
            assertFalse(fs.exists(savePath), "unverified bytes must never reach the user-visible path")
            assertFalse(fs.exists(partialPath), "the mismatched partial must be deleted")
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            if (fs.exists(partialPath)) fs.delete(partialPath)
        }
    }

    @Test
    fun http416_withMatchingChecksum_stillFinalizesThePartial() = runTest {
        // Positive control: adding verification must not break the legitimate resume-complete
        // case the 416 branch exists to serve.
        val fs = FileSystem.SYSTEM
        val savePath = "v350_416_ok_${kotlin.random.Random.nextInt()}.bin".toPath()
        val partialPath = "$savePath.partial".toPath()
        fs.write(partialPath) { writeUtf8("hello world") }

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.RequestedRangeNotSatisfiable) }
        try {
            val result = HttpDownloadWorker(HttpClient(engine) { install(HttpTimeout) }, fs)
                .doWork(downloadConfig(savePath.toString(), helloWorldSha256), WorkerEnvironment(null) { false })

            assertIs<WorkerResult.Success>(result)
            assertTrue(fs.exists(savePath))
            assertEquals("hello world", fs.source(savePath).buffer().readUtf8())
        } finally {
            if (fs.exists(savePath)) fs.delete(savePath)
            if (fs.exists(partialPath)) fs.delete(partialPath)
        }
    }

    // ── #11 — the only HTTP worker that converted cancellation into a failure ──────────
    //
    // kotlinx's CancellationException is a RuntimeException on every target, so
    // HttpRequestWorker's two `catch (e: Exception)` arms caught it. A task the scheduler
    // merely pre-empted therefore produced a TaskCompletionEvent(success = false) in the
    // event store and — from executeRequest's arm — `shouldRetry = true`, re-arming work
    // that was deliberately stopped. Every sibling worker already rethrows.

    @Test
    fun httpRequest_cancellation_propagates_ratherThanBecomingAFailure() = runTest {
        val engine = MockEngine { throw CancellationException("pre-empted by the scheduler") }
        val worker = HttpRequestWorker(HttpClient(engine) { install(HttpTimeout) })
        val input = HttpWorkerJson.encodeToString(HttpRequestConfig(url = "https://example.com/api"))

        assertFailsWith<CancellationException> {
            worker.doWork(input, WorkerEnvironment())
        }
    }
}
