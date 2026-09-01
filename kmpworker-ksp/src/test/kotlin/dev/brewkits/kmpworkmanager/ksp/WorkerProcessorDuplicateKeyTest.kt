package dev.brewkits.kmpworkmanager.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName
import org.junit.Test
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerProcessor.validateNoDuplicateKeys] — the guard against two
 * `@Worker` classes silently overwriting each other's entry in the generated
 * `providers` map (discovered during a senior review pass, no prior coverage).
 *
 * **Why this bypasses [WorkerProcessorTest]'s compile-testing harness:** that entire
 * class is `@Ignore`d — kctfork 0.6.0 never invokes [WorkerProcessor] for in-memory
 * `SourceFile`s (see its class KDoc), so none of its 21 tests actually run in CI today.
 * Rather than add a 22nd test to a harness that silently never executes, this calls
 * [WorkerProcessor.validateNoDuplicateKeys] directly against synthetic [WorkerInfo]
 * lists with a fake [KSPLogger] recording calls — exercising the real production
 * method, just without going through full annotation processing. This test DOES run.
 */
class WorkerProcessorDuplicateKeyTest {

    /** Records every `error()` call; the other [KSPLogger] methods are no-ops. */
    private class RecordingLogger : KSPLogger {
        val errors = mutableListOf<String>()
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?) {}
        override fun error(message: String, symbol: KSNode?) { errors.add(message) }
        override fun exception(e: Throwable) {}
    }

    /** [CodeGenerator] is never invoked by [WorkerProcessor.validateNoDuplicateKeys]. */
    private class UnusedCodeGenerator : CodeGenerator {
        override val generatedFile get() = emptySet<java.io.File>()
        override fun associate(sources: List<com.google.devtools.ksp.symbol.KSFile>, packageName: String, fileName: String, extensionName: String) {}
        override fun associateByPath(sources: List<com.google.devtools.ksp.symbol.KSFile>, path: String, extensionName: String) {}
        override fun associateWithClasses(classes: List<com.google.devtools.ksp.symbol.KSClassDeclaration>, packageName: String, fileName: String, extensionName: String) {}
        override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String): OutputStream =
            throw UnsupportedOperationException("not used by validateNoDuplicateKeys")
        override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): OutputStream =
            throw UnsupportedOperationException("not used by validateNoDuplicateKeys")
    }

    private fun worker(name: String, className: String, aliases: List<String> = emptyList()) =
        WorkerInfo(
            name = name,
            className = ClassName("dev.brewkits.test", className),
            bgTaskId = "",
            aliases = aliases
        )

    private fun processorWith(logger: RecordingLogger) = WorkerProcessor(UnusedCodeGenerator(), logger)

    @Test
    fun `distinct names produce no errors`() {
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(worker("SyncWorker", "SyncWorker"), worker("UploadWorker", "UploadWorker")),
            "Android"
        )
        assertTrue(logger.errors.isEmpty(), "distinct names must not error, got: ${logger.errors}")
    }

    @Test
    fun `two classes with the same explicit name is an error`() {
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(worker("SyncWorker", "FirstSyncWorker"), worker("SyncWorker", "SecondSyncWorker")),
            "Android"
        )
        assertEquals(1, logger.errors.size, "exactly one collision must be reported, got: ${logger.errors}")
        assertTrue(logger.errors[0].contains("SyncWorker"), "error must name the colliding key")
        assertTrue(logger.errors[0].contains("FirstSyncWorker"), "error must name a colliding class")
        assertTrue(logger.errors[0].contains("SecondSyncWorker"), "error must name the other colliding class")
    }

    @Test
    fun `an alias colliding with another worker's canonical name is an error`() {
        // The exact shape a dev could hit by accident: renaming SyncWorker to SyncWorkerV2
        // with aliases = ["SyncWorker"] for backward compatibility, while a *different*,
        // unrelated worker already happens to be named "SyncWorker".
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(
                worker("SyncWorker", "LegacySyncWorker"),
                worker("SyncWorkerV2", "SyncWorkerV2", aliases = listOf("SyncWorker"))
            ),
            "Android"
        )
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors[0].contains("\"SyncWorker\""))
    }

    @Test
    fun `two aliases colliding with each other is an error`() {
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(
                worker("WorkerA", "WorkerA", aliases = listOf("OldName")),
                worker("WorkerB", "WorkerB", aliases = listOf("OldName"))
            ),
            "iOS"
        )
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors[0].contains("OldName"))
        assertTrue(logger.errors[0].contains("iOS"), "platform must be identified in the message")
    }

    @Test
    fun `the same class listing its own name as an alias is not an error`() {
        // Redundant, not harmful — put(key){SameClass()} twice with the same class loses
        // nothing. Only flag when DIFFERENT classes claim the same key.
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(worker("SyncWorker", "SyncWorker", aliases = listOf("SyncWorker"))),
            "Android"
        )
        assertTrue(logger.errors.isEmpty(), "self-collision must not error, got: ${logger.errors}")
    }

    @Test
    fun `multiple independent collisions are all reported`() {
        val logger = RecordingLogger()
        processorWith(logger).validateNoDuplicateKeys(
            listOf(
                worker("A", "ClassA1"), worker("A", "ClassA2"),
                worker("B", "ClassB1"), worker("B", "ClassB2"),
                worker("C", "ClassC1")
            ),
            "Android"
        )
        assertEquals(2, logger.errors.size, "both A and B collisions must be reported independently")
    }
}
