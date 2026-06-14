package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test for [HttpWorkerRegistry] — the factory introduced when the Ktor HTTP
 * workers were split into the `kmpworkmanager-http` artifact (v3.0.0).
 *
 * Pins that the registry resolves each HTTP worker by both simple and fully-qualified
 * name, and returns null for anything it doesn't own (so `CompositeWorkerFactory` can
 * fall through to the next factory). The stored `workerClassName` task IDs depend on
 * these exact strings, so a rename here would silently orphan persisted tasks.
 */
class HttpWorkerRegistryTest {

    private val simpleNames = listOf(
        "HttpRequestWorker",
        "HttpSyncWorker",
        "HttpDownloadWorker",
        "HttpUploadWorker",
    )

    @Test
    fun resolvesEachHttpWorker_bySimpleName() {
        assertTrue(HttpWorkerRegistry.createWorker("HttpRequestWorker") is HttpRequestWorker)
        assertTrue(HttpWorkerRegistry.createWorker("HttpSyncWorker") is HttpSyncWorker)
        assertTrue(HttpWorkerRegistry.createWorker("HttpDownloadWorker") is HttpDownloadWorker)
        assertTrue(HttpWorkerRegistry.createWorker("HttpUploadWorker") is HttpUploadWorker)
    }

    @Test
    fun resolvesEachHttpWorker_byFullyQualifiedName() {
        for (simple in simpleNames) {
            val fqn = "dev.brewkits.kmpworkmanager.workers.builtins.$simple"
            assertTrue(
                HttpWorkerRegistry.createWorker(fqn) != null,
                "FQN '$fqn' must resolve — persisted task IDs use the fully-qualified form",
            )
        }
    }

    @Test
    fun returnsNull_forUnknownOrNonHttpWorker() {
        assertNull(HttpWorkerRegistry.createWorker("FileCompressionWorker"))
        assertNull(HttpWorkerRegistry.createWorker("NotAWorker"))
        assertNull(HttpWorkerRegistry.createWorker(""))
    }

    @Test
    fun listWorkers_matchesResolvableNames() {
        val listed = HttpWorkerRegistry.listWorkers()
        assertTrue(listed.size == simpleNames.size, "listWorkers must enumerate all HTTP workers")
        for (fqn in listed) {
            assertTrue(
                HttpWorkerRegistry.createWorker(fqn) != null,
                "every listed worker name '$fqn' must be resolvable",
            )
        }
    }
}
