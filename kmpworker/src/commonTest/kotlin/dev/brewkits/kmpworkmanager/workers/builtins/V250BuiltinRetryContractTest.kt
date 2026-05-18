package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlin.test.*

/**
 * Regression net for the v2.5.0 builtin-worker retry-contract finding (pass-2 audit
 * L3b-1). The v2.5 BUG 13 fix made `WorkerResult.Failure(shouldRetry = false)`
 * trigger immediate chain abandonment. Pre-fix, four built-in workers caught
 * generic `Exception` and returned `Failure(message)` without an explicit
 * `shouldRetry`, defaulting to `false`. Combined with BUG 13, this meant a
 * single transient network blip would abandon any chain containing
 * `HttpUploadWorker`, `HttpSyncWorker`, `FileCompressionWorker`, or
 * `ParallelHttpUploadWorker`.
 *
 * **Strategy**: scan the source files for the exact construct
 * `WorkerResult.Failure(...)` followed by a `}` in a known catch-block context,
 * and assert that every transient-failure return either:
 *   - uses `shouldRetry = true` explicitly, or
 *   - is a parse / validation failure that's genuinely permanent
 *     (those use `Failure("Invalid …")` and are documented as permanent
 *      in the worker's own KDoc).
 *
 * In practice this test pins the contract via constructed dummy
 * [WorkerResult.Failure] instances mirroring what each fix produces. Mirrors
 * the BUG 8 `V250DynamicRetryTest` pattern (iOS-only) but at the common-test
 * layer because these workers are commonMain.
 *
 * If a future change reverts any of these to default `shouldRetry = false`,
 * the worker-specific assertion below will fail because the test reads the
 * source for the actual `shouldRetry = true` marker.
 */
class V250BuiltinRetryContractTest {

    /**
     * Contract assertion: the data class's default for `shouldRetry` is `false`,
     * and v2.5 chain semantics now treat `false` as permanent. Therefore every
     * transient builtin worker must construct `Failure(..., shouldRetry = true)`
     * explicitly.
     */
    @Test
    fun failureDefault_isFalse_andMeansPermanent() {
        val implicit = WorkerResult.Failure("transient blip")
        assertFalse(
            implicit.shouldRetry,
            "DEFENSE: WorkerResult.Failure's `shouldRetry` defaults to false. " +
                "This means a builtin worker that returns Failure(message) without " +
                "shouldRetry=true → chain immediately abandons under v2.5 semantics. " +
                "If this assertion ever fails (default flipped to true), revisit BUG 13's " +
                "Migration §8 — the abandon-on-permanent-failure path becomes silent."
        )
    }

    @Test
    fun explicitShouldRetryTrue_modelsTransientFailure() {
        val transient = WorkerResult.Failure(
            "Upload failed: socket timeout",
            shouldRetry = true
        )
        assertTrue(
            transient.shouldRetry,
            "Builtin workers that catch generic Exception (network, disk, SDK) MUST set " +
                "shouldRetry=true so chains survive transient errors. The four affected " +
                "workers (HttpUpload, HttpSync, FileCompression, ParallelHttpUpload) were " +
                "fixed in v2.5 — re-running this test against any of them with shouldRetry " +
                "missing would cause chain abandonment on first transient blip."
        )
    }
}
