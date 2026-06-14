package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test for the core [BuiltinWorkerRegistry] after the HTTP workers were split
 * into the `kmpworkmanager-http` artifact (v3.0.0).
 *
 * The core registry must now resolve ONLY the non-HTTP built-ins (FileCompressionWorker)
 * and must return null for the HTTP worker names — those moved to `HttpWorkerRegistry`.
 * This pins the split so a future change can't silently re-add an HTTP worker here (which
 * would drag Ktor back into the core module).
 */
class BuiltinWorkerRegistryTest {

    @Test
    fun resolvesFileCompressionWorker_bySimpleAndFqn() {
        assertTrue(BuiltinWorkerRegistry.createWorker("FileCompressionWorker") is FileCompressionWorker)
        assertTrue(
            BuiltinWorkerRegistry.createWorker(
                "dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker",
            ) is FileCompressionWorker,
        )
    }

    @Test
    fun returnsNull_forHttpWorkers_nowInSeparateArtifact() {
        // These moved to kmpworkmanager-http / HttpWorkerRegistry. The core registry must
        // NOT know them — otherwise the core module would still need Ktor.
        for (http in listOf(
            "HttpRequestWorker",
            "HttpSyncWorker",
            "HttpDownloadWorker",
            "HttpUploadWorker",
            "ParallelHttpDownloadWorker",
            "ParallelHttpUploadWorker",
        )) {
            assertNull(
                BuiltinWorkerRegistry.createWorker(http),
                "$http moved to kmpworkmanager-http; core registry must return null",
            )
        }
    }

    @Test
    fun listWorkers_containsOnlyFileCompression() {
        val listed = BuiltinWorkerRegistry.listWorkers()
        assertTrue(listed.size == 1, "core built-ins should be just FileCompressionWorker, was $listed")
        assertTrue(listed.single().endsWith("FileCompressionWorker"))
    }
}
