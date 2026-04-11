package dev.brewkits.kmpworkmanager.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspIncremental
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for KSP [WorkerProcessor].
 *
 * Covers:
 * - Android and iOS factory code generation
 * - `providers: MutableMap` pattern (DI-overridable)
 * - Custom worker names and default (class-name) fallback
 * - Multiple workers + mixed-platform compilation
 * - `bgTaskId` extraction and `BgTaskIdProvider` generation
 * - `requiredBgTaskIds` only generated when at least one bgTaskId is non-empty
 * - Auto-generated file header content
 * - No-worker case (no factories generated)
 * - Indirect inheritance: class extends intermediate base that extends AndroidWorker/IosWorker
 * - Wrong base class: @Worker on a class that doesn't extend AndroidWorker or IosWorker
 * - Stress test: 50 workers in < 30 s
 *
 * **Why this class is @Ignored:**
 * kctfork 0.6.0 (`dev.zacsweers.kctfork`) does not invoke the [WorkerProcessor] for
 * in-memory [com.tschuchort.compiletesting.SourceFile] sources. The KSP output directory
 * (`ksp/sources/kotlin/`) is created but remains empty even with `useKsp2()` or
 * `kspWithCompilation = true`. Root cause: version mismatch between the KSP API on the
 * compile classpath (`symbol-processing-api 2.1.0-1.0.29`) and the runtime bundled in
 * kctfork (`symbol-processing-aa-embeddable 2.0.21-1.0.27`).
 *
 * The processor is verified correct by the generated factory files produced during real
 * Gradle builds (see `AndroidWorkerFactoryGenerated.kt` / `IosWorkerFactoryGenerated.kt`
 * in build outputs). Re-enable once kctfork ships a compatible version.
 */
@Suppress("unused")
@org.junit.Ignore("kctfork 0.6.0: KSP processor not triggered for in-memory SourceFiles — see class KDoc for details")
@OptIn(ExperimentalCompilerApi::class)
class WorkerProcessorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prepareCompilation(vararg sourceFiles: SourceFile): KotlinCompilation =
        KotlinCompilation().apply {
            sources = listOf(
                workerAnnotationSource(),
                androidWorkerSource(),
                iosWorkerSource(),
                bgTaskIdProviderSource()
            ) + sourceFiles.toList()
            symbolProcessorProviders = mutableListOf(WorkerProcessorProvider())
            kspWithCompilation = true
            kspIncremental = false
            languageVersion = "2.1"
            inheritClassPath = true
            messageOutputStream = System.out
        }

    private fun JvmCompilationResult.kspGeneratedFile(fileName: String): File {
        var baseDir = outputDirectory
        while (baseDir != null && !baseDir.name.startsWith("Kotlin-Compilation")) {
            baseDir = baseDir.parentFile
        }
        val searchDir = baseDir ?: outputDirectory.parentFile
        return searchDir.walkTopDown().firstOrNull { it.name == fileName }
            ?: throw IllegalStateException("Generated file $fileName not found in $searchDir. Contents: \n${searchDir.walkTopDown().joinToString("\n")}")
    }

    /** Stub source snippet declaring the @Worker annotation with all parameters. */
    private fun workerAnnotationSource() = SourceFile.kotlin("WorkerAnnotation.kt", """
        package dev.brewkits.kmpworkmanager.annotations
        annotation class Worker(val name: String = "", val bgTaskId: String = "", val aliases: Array<String> = [])
    """.trimIndent())

    private fun androidWorkerSource() = SourceFile.kotlin("AndroidWorkerStub.kt", """
        package dev.brewkits.kmpworkmanager.background.domain
        interface AndroidWorker
        interface AndroidWorkerFactory {
            fun createWorker(workerClassName: String): AndroidWorker?
        }
    """.trimIndent())

    private fun iosWorkerSource() = SourceFile.kotlin("IosWorkerStub.kt", """
        package dev.brewkits.kmpworkmanager.background.data
        interface IosWorker
        interface IosWorkerFactory {
            fun createWorker(workerClassName: String): IosWorker?
        }
    """.trimIndent())

    private fun bgTaskIdProviderSource() = SourceFile.kotlin("BgTaskIdProviderStub.kt", """
        package dev.brewkits.kmpworkmanager.background.domain
        interface BgTaskIdProvider {
            val requiredBgTaskIds: Set<String>
        }
    """.trimIndent())

    // ── Android factory ───────────────────────────────────────────────────────

    @Test
    fun testCodeGeneration_SingleAndroidWorker() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()
        println("COMPILATION MESSAGES:\n" + result.messages)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")

        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("class AndroidWorkerFactoryGenerated"),    "Factory class must be generated")
        assertTrue(content.contains("providers"),                               "Must use providers map pattern")
        assertTrue(content.contains("\"SyncWorker\""),                          "Worker name must appear in map")
        assertTrue(content.contains("SyncWorker()"),                            "Worker constructor must be in lambda")
        assertFalse(content.contains("-> SyncWorker()"),                        "Old when-expression style must NOT be present")
    }

    @Test
    fun testCodeGeneration_SingleIosWorker() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker")
            class SyncWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("class IosWorkerFactoryGenerated"), "iOS factory class must be generated")
        assertTrue(content.contains("\"SyncWorker\""),                  "Worker name in providers map")
        assertTrue(content.contains("SyncWorker()"),                    "Worker constructor in lambda")
    }

    @Test
    fun testCodeGeneration_MultipleWorkers() {
        val source = SourceFile.kotlin("TestWorkers.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")    class SyncWorker    : AndroidWorker
            @Worker("UploadWorker")  class UploadWorker  : AndroidWorker
            @Worker("DatabaseWorker")class DatabaseWorker: AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"SyncWorker\""),     "SyncWorker must be in providers map")
        assertTrue(content.contains("\"UploadWorker\""),   "UploadWorker must be in providers map")
        assertTrue(content.contains("\"DatabaseWorker\""), "DatabaseWorker must be in providers map")
    }

    @Test
    fun testCodeGeneration_CustomWorkerName() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("my-custom-sync-worker")
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"my-custom-sync-worker\""), "Custom name must appear in providers map")
    }

    @Test
    fun testCodeGeneration_DefaultWorkerName_UsesClassSimpleName() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker  // no explicit name
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"SyncWorker\""), "Should default to class simple name when name is empty")
    }

    @Test
    fun testCodeGeneration_MixedPlatforms() {
        val source = SourceFile.kotlin("TestWorkers.kt", """
             
            
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("AndroidSync") class AndroidSyncWorker : AndroidWorker
            @Worker("IosSync")     class IosSyncWorker     : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val androidContent = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        val iosContent     = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()

        assertTrue(androidContent.contains("\"AndroidSync\""), "Android factory must contain AndroidSync")
        assertTrue(iosContent.contains("\"IosSync\""),         "iOS factory must contain IosSync")

        // Cross-platform isolation: Android factory must not contain iOS workers and vice versa
        assertFalse(androidContent.contains("\"IosSync\""),    "Android factory must NOT contain IosSync")
        assertFalse(iosContent.contains("\"AndroidSync\""),    "iOS factory must NOT contain AndroidSync")
    }

    // ── bgTaskId + BgTaskIdProvider ───────────────────────────────────────────

    @Test
    fun testCodeGeneration_IosWorker_WithBgTaskId_ImplementsBgTaskIdProvider() {
        val source = SourceFile.kotlin("TestWorkers.kt", """
             
            
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker", bgTaskId = "com.example.sync-task")
            class SyncWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("BgTaskIdProvider"),                      "Must implement BgTaskIdProvider")
        assertTrue(content.contains("requiredBgTaskIds"),                     "Must declare requiredBgTaskIds")
        assertTrue(content.contains("com.example.sync-task"),                 "Must include the bgTaskId value")
    }

    @Test
    fun testCodeGeneration_IosWorker_WithoutBgTaskId_NoBgTaskIdProvider() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker")   // no bgTaskId
            class SyncWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()

        assertFalse(content.contains("BgTaskIdProvider"), "Must NOT implement BgTaskIdProvider when no bgTaskId")
        assertFalse(content.contains("requiredBgTaskIds"), "Must NOT declare requiredBgTaskIds when no bgTaskId")
    }

    @Test
    fun testCodeGeneration_IosWorker_MultipleBgTaskIds() {
        val source = SourceFile.kotlin("TestWorkers.kt", """
             
            
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker",   bgTaskId = "com.example.sync")
            class SyncWorker : IosWorker

            @Worker("UploadWorker", bgTaskId = "com.example.upload")
            class UploadWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("com.example.sync"),   "First bgTaskId must be present")
        assertTrue(content.contains("com.example.upload"), "Second bgTaskId must be present")
        assertTrue(content.contains("BgTaskIdProvider"),   "Must implement BgTaskIdProvider")
    }

    @Test
    fun testCodeGeneration_IosWorker_MixedBgTaskIds_OnlyNonEmptyInSet() {
        // One worker has bgTaskId, the other doesn't — only the non-empty one appears in requiredBgTaskIds
        val source = SourceFile.kotlin("TestWorkers.kt", """
             
            
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker",   bgTaskId = "com.example.sync")
            class SyncWorker : IosWorker

            @Worker("CacheWorker")  // no bgTaskId
            class CacheWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("BgTaskIdProvider"),   "Must implement BgTaskIdProvider (at least one has bgTaskId)")
        assertTrue(content.contains("com.example.sync"),   "Non-empty bgTaskId must be in requiredBgTaskIds")
        assertFalse(content.contains("setOf(\"\")"),        "Empty bgTaskId must NOT appear in requiredBgTaskIds")
    }

    @Test
    fun testCodeGeneration_AndroidWorker_BgTaskIdIgnored() {
        // Android workers don't use bgTaskId — it should be ignored silently
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker", bgTaskId = "com.example.sync")
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()

        assertFalse(content.contains("BgTaskIdProvider"), "Android factory must NOT implement BgTaskIdProvider")
        assertFalse(content.contains("requiredBgTaskIds"), "Android factory must NOT have requiredBgTaskIds")
    }

    // ── No-worker case ────────────────────────────────────────────────────────

    @Test
    fun testCodeGeneration_NoWorkers() {
        val source = SourceFile.kotlin("TestClass.kt", """
            package dev.brewkits.test
            class RegularClass { fun doSomething() {} }
        """.trimIndent())

        val compilation = prepareCompilation(source)
        val result      = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFiles = compilation.kspSourcesDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(generatedFiles.isEmpty(), "No factories should be generated when there are no @Worker annotations")
    }

    // ── Auto-generated file header ────────────────────────────────────────────

    @Test
    fun testCodeGeneration_GeneratedFileHeader() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("TestWorker")
            class TestWorker : AndroidWorker
        """.trimIndent())

        val result  = prepareCompilation(source).compile()
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("AUTO-GENERATED by KSP WorkerProcessor"),  "Header must contain processor name")
        assertTrue(content.contains("do not edit manually"),                    "Header must warn against manual edits")
        assertTrue(content.contains("Regenerate by rebuilding the project"),    "Header must explain how to regenerate")
    }

    // ── Providers map (DI override) ───────────────────────────────────────────

    @Test
    fun testCodeGeneration_ProvidersMapIsMutable() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result  = prepareCompilation(source).compile()
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("MutableMap"), "providers must be MutableMap so consumers can override entries")
        assertTrue(content.contains("mutableMapOf"), "providers must be initialised with mutableMapOf()")
    }

    @Test
    fun testCodeGeneration_CreateWorkerDelegatesToProviders() {
        val source = SourceFile.kotlin("TestWorker.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result  = prepareCompilation(source).compile()
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()

        assertTrue(content.contains("providers[workerClassName]?.invoke()"),
            "createWorker must delegate to providers map via providers[key]?.invoke()")
    }

    // ── Stress test ───────────────────────────────────────────────────────────

    @Test
    fun stressTestManyWorkers() {
        val workerCount   = 50
        val workerClasses = (1..workerCount).joinToString("\n") { i ->
            "@Worker(\"Worker$i\") class Worker$i : AndroidWorker"
        }

        val source = SourceFile.kotlin("ManyWorkers.kt", """
             
            

            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            $workerClasses
        """.trimIndent())

        val startTime = System.currentTimeMillis()
        val result    = prepareCompilation(source).compile()
        val duration  = System.currentTimeMillis() - startTime

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        for (i in 1..workerCount) {
            assertTrue(content.contains("\"Worker$i\""), "Providers map must contain Worker$i")
        }

        assertTrue(duration < 30_000, "50-worker factory must compile in <30s (took ${duration}ms)")
        println("Stress test: $workerCount workers compiled in ${duration}ms")
    }

    // ── Indirect inheritance ──────────────────────────────────────────────────

    /**
     * Regression test for the direct-supertype-only bug.
     *
     * Before the fix, WorkerProcessor used:
     *   classDecl.superTypes.map { simpleName }.contains("AndroidWorker")
     * which only checked the immediate parent. A class like:
     *   class MyWorker : BaseAppWorker()
     *   open class BaseAppWorker : AndroidWorker
     * would be silently skipped — never added to the factory, causing "worker not found" at runtime.
     *
     * After the fix, extendsWorkerType() traverses the full hierarchy.
     */
    @Test
    fun testCodeGeneration_IndirectAndroidInheritance_GeneratesFactory() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            // Intermediate base class — does NOT have @Worker
            open class BaseAppWorker : AndroidWorker

            // Concrete worker two levels deep — MUST appear in the generated factory
            @Worker("DeepWorker")
            class DeepWorker : BaseAppWorker()
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation must succeed")
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"DeepWorker\""),
            "Indirectly-inherited AndroidWorker must appear in generated factory")
        assertTrue(content.contains("DeepWorker()"),
            "DeepWorker constructor must be in the providers lambda")
    }

    @Test
    fun testCodeGeneration_IndirectIosInheritance_GeneratesFactory() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            open class BaseIosWorker : IosWorker

            @Worker("DeepIosWorker")
            class DeepIosWorker : BaseIosWorker()
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"DeepIosWorker\""),
            "Indirectly-inherited IosWorker must appear in generated factory")
    }

    // ── Wrong base class ──────────────────────────────────────────────────────

    /**
     * @Worker on a class that doesn't extend AndroidWorker or IosWorker must NOT generate
     * a factory entry and must emit a KSP warning (not an error — compilation continues).
     *
     * Without this, a developer who misplaces @Worker on a plain class would get no factory
     * entry, then a cryptic "worker not found" crash at runtime instead of a build-time signal.
     */
    @Test
    fun testCodeGeneration_WrongBaseClass_NoFactoryGenerated() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker

            // Deliberately NOT extending AndroidWorker or IosWorker
            @Worker("BrokenWorker")
            class BrokenWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        // Compilation must still succeed (warning, not error) so a single bad annotation
        // doesn't break the entire build
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode,
            "Wrong base class should be a KSP warning, not a compile error")

        // KSP warns via logger.warn — check the message output
        assertTrue(result.messages.contains("doesn't extend AndroidWorker or IosWorker"),
            "Must emit KSP warning for @Worker on a class not extending the required base")

        // No factory should be generated for this broken worker
        val generatedFiles = result.outputDirectory.parentFile
            ?.walkTopDown()
            ?.filter { it.isFile && it.name.endsWith(".kt") }
            ?.toList() ?: emptyList()
        assertFalse(generatedFiles.any { it.readText().contains("\"BrokenWorker\"") },
            "BrokenWorker must NOT appear in any generated factory")
    }

    // ── No-explicit-name warning (KSP-2) ─────────────────────────────────────

    /**
     * @Worker without an explicit name defaults to the simple class name and emits
     * a KSP warning pointing to the risk of rename/ProGuard breakage.
     */
    @Test
    fun testCodeGeneration_NoExplicitName_EmitsWarningAndUsesSimpleName() {
        val source = SourceFile.kotlin("TestWorker.kt", """



            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker  // no explicit name — should warn
            class RenameRiskWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode,
            "Missing name must be a warning, not an error")
        assertTrue(result.messages.contains("no explicit `name`"),
            "KSP must warn about missing explicit name")
        assertTrue(result.messages.contains("RenameRiskWorker"),
            "Warning must include the class name")
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"RenameRiskWorker\""),
            "Factory key must default to simple class name when name is omitted")
    }

    // ── Aliases ───────────────────────────────────────────────────────────────

    @Test
    fun testCodeGeneration_AndroidWorker_WithAlias_BothNamesInFactory() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker(name = "NewSyncWorker", aliases = ["OldSyncWorker"])
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"NewSyncWorker\""), "Canonical name must be in factory")
        assertTrue(content.contains("\"OldSyncWorker\""), "Alias must also be in factory")
        // Both entries should point to the same class
        val newEntry = content.substringAfter("\"NewSyncWorker\"").substringBefore("\n")
        val oldEntry = content.substringAfter("\"OldSyncWorker\"").substringBefore("\n")
        assertTrue(newEntry.contains("SyncWorker()"), "Canonical entry must instantiate SyncWorker")
        assertTrue(oldEntry.contains("SyncWorker()"), "Alias entry must also instantiate SyncWorker")
    }

    @Test
    fun testCodeGeneration_IosWorker_WithAlias_BothNamesInFactory() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker(name = "NewFetchWorker", aliases = ["OldFetchWorker"])
            class FetchWorker : IosWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"NewFetchWorker\""), "Canonical name must be in iOS factory")
        assertTrue(content.contains("\"OldFetchWorker\""), "Alias must also be in iOS factory")
        assertTrue(content.substringAfter("\"OldFetchWorker\"").substringBefore("\n").contains("FetchWorker()"),
            "Alias entry must instantiate the same class as the canonical entry")
    }

    @Test
    fun testCodeGeneration_MultipleAliases_AllNamesInFactory() {
        val source = SourceFile.kotlin("TestWorker.kt", """
            package dev.brewkits.test
            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker(name = "SyncWorkerV3", aliases = ["SyncWorkerV1", "SyncWorkerV2"])
            class SyncWorker : AndroidWorker
        """.trimIndent())

        val result = prepareCompilation(source).compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val content = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt").readText()
        assertTrue(content.contains("\"SyncWorkerV3\""), "Canonical name V3 must be in factory")
        assertTrue(content.contains("\"SyncWorkerV1\""), "Alias V1 must be in factory")
        assertTrue(content.contains("\"SyncWorkerV2\""), "Alias V2 must be in factory")
        // All three entries point to the same class
        val v1Entry = content.substringAfter("\"SyncWorkerV1\"").substringBefore("\n")
        val v2Entry = content.substringAfter("\"SyncWorkerV2\"").substringBefore("\n")
        assertTrue(v1Entry.contains("SyncWorker()"), "V1 alias must instantiate SyncWorker")
        assertTrue(v2Entry.contains("SyncWorker()"), "V2 alias must instantiate SyncWorker")
    }
}
