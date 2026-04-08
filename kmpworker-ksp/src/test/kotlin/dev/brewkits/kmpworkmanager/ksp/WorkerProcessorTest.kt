package dev.brewkits.kmpworkmanager.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspIncremental
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
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
 * - Stress test: 50 workers in < 30 s
 */
@OptIn(ExperimentalCompilerApi::class)
@org.junit.Ignore("kctfork 0.6.0 with KSP2 fails to trigger SymbolProcessor properly for in-memory SourceFiles. Processor works fine in real builds.")
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
            useKsp2()
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

    /** Stub source snippet declaring the @Worker annotation with both parameters. */
    private fun workerAnnotationSource() = SourceFile.kotlin("WorkerAnnotation.kt", """
        package dev.brewkits.kmpworkmanager.annotations
        annotation class Worker(val name: String = "", val bgTaskId: String = "")
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
}
