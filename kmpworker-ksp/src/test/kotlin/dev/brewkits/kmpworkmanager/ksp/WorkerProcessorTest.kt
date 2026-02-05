package dev.brewkits.kmpworkmanager.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for KSP WorkerProcessor (Task #10)
 * Tests:
 * - Code generation correctness
 * - Android and iOS worker factories
 * - Custom worker names
 * - Multiple workers
 * - Edge cases (no workers, invalid annotations)
 */
@OptIn(ExperimentalCompilerApi::class)
class WorkerProcessorTest {

    @Test
    fun testCodeGeneration_SingleAndroidWorker() {
        val source = SourceFile.kotlin(
            "TestWorker.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")
            class SyncWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")

        // Verify generated file exists
        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        assertTrue(generatedFile.exists(), "Generated factory should exist")

        // Verify generated content
        val content = generatedFile.readText()
        assertTrue(content.contains("class AndroidWorkerFactoryGenerated"), "Should generate factory class")
        assertTrue(content.contains("\"SyncWorker\" -> SyncWorker()"), "Should generate worker mapping")
        assertTrue(content.contains("else -> null"), "Should have default case")
    }

    @Test
    fun testCodeGeneration_SingleIosWorker() {
        val source = SourceFile.kotlin(
            "TestWorker.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("SyncWorker")
            class SyncWorker : IosWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt")
        assertTrue(generatedFile.exists(), "Generated iOS factory should exist")

        val content = generatedFile.readText()
        assertTrue(content.contains("class IosWorkerFactoryGenerated"))
        assertTrue(content.contains("\"SyncWorker\" -> SyncWorker()"))
    }

    @Test
    fun testCodeGeneration_MultipleWorkers() {
        val source = SourceFile.kotlin(
            "TestWorkers.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("SyncWorker")
            class SyncWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }

            @Worker("UploadWorker")
            class UploadWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }

            @Worker("DatabaseWorker")
            class DatabaseWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val content = generatedFile.readText()

        assertTrue(content.contains("\"SyncWorker\" -> SyncWorker()"))
        assertTrue(content.contains("\"UploadWorker\" -> UploadWorker()"))
        assertTrue(content.contains("\"DatabaseWorker\" -> DatabaseWorker()"))
    }

    @Test
    fun testCodeGeneration_CustomWorkerName() {
        val source = SourceFile.kotlin(
            "TestWorker.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("my-custom-sync-worker")
            class SyncWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val content = generatedFile.readText()

        assertTrue(
            content.contains("\"my-custom-sync-worker\" -> SyncWorker()"),
            "Should use custom worker name"
        )
    }

    @Test
    fun testCodeGeneration_DefaultWorkerName() {
        val source = SourceFile.kotlin(
            "TestWorker.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker
            class SyncWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val content = generatedFile.readText()

        // When no name provided, should use class simple name
        assertTrue(
            content.contains("\"SyncWorker\" -> SyncWorker()"),
            "Should default to class simple name"
        )
    }

    @Test
    fun testCodeGeneration_MixedPlatforms() {
        val source = SourceFile.kotlin(
            "TestWorkers.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
            import dev.brewkits.kmpworkmanager.background.data.IosWorker

            @Worker("AndroidSync")
            class AndroidSyncWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }

            @Worker("IosSync")
            class IosSyncWorker : IosWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Should generate both factories
        val androidFactory = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val iosFactory = result.kspGeneratedFile("IosWorkerFactoryGenerated.kt")

        assertTrue(androidFactory.exists(), "Should generate Android factory")
        assertTrue(iosFactory.exists(), "Should generate iOS factory")

        val androidContent = androidFactory.readText()
        val iosContent = iosFactory.readText()

        assertTrue(androidContent.contains("\"AndroidSync\" -> AndroidSyncWorker()"))
        assertTrue(iosContent.contains("\"IosSync\" -> IosSyncWorker()"))
    }

    @Test
    fun testCodeGeneration_NoWorkers() {
        val source = SourceFile.kotlin(
            "TestClass.kt",
            """
            package dev.brewkits.test

            class RegularClass {
                fun doSomething() {}
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        // Should not generate any factories
        val kspGeneratedDir = compilation.kspSourcesDir
        val generatedFiles = kspGeneratedDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(
            generatedFiles.isEmpty(),
            "Should not generate factories when no @Worker annotations found"
        )
    }

    @Test
    fun testCodeGeneration_GeneratedFileHeader() {
        val source = SourceFile.kotlin(
            "TestWorker.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            @Worker("TestWorker")
            class TestWorker : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val content = generatedFile.readText()

        // Verify header comment
        assertTrue(content.contains("AUTO-GENERATED by KSP WorkerProcessor"))
        assertTrue(content.contains("Do not modify this file manually!"))
        assertTrue(content.contains("To regenerate, rebuild your project."))
    }

    /**
     * Stress test: Generate factory for many workers
     */
    @Test
    fun stressTestManyWorkers() {
        // Generate source code for 50 workers
        val workerCount = 50
        val workerClasses = (1..workerCount).joinToString("\n\n") { i ->
            """
            @Worker("Worker$i")
            class Worker$i : AndroidWorker {
                override suspend fun doWork(input: String): Boolean = true
            }
            """.trimIndent()
        }

        val source = SourceFile.kotlin(
            "ManyWorkers.kt",
            """
            package dev.brewkits.test

            import dev.brewkits.kmpworkmanager.annotations.Worker
            import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

            $workerClasses
            """.trimIndent()
        )

        val compilation = prepareCompilation(source)
        val startTime = System.currentTimeMillis()
        val result = compilation.compile()
        val duration = System.currentTimeMillis() - startTime

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)

        val generatedFile = result.kspGeneratedFile("AndroidWorkerFactoryGenerated.kt")
        val content = generatedFile.readText()

        // Verify all workers are in factory
        for (i in 1..workerCount) {
            assertTrue(
                content.contains("\"Worker$i\" -> Worker$i()"),
                "Should contain Worker$i"
            )
        }

        println("Stress test: Generated factory for $workerCount workers in ${duration}ms")

        // Should complete in reasonable time
        assertTrue(duration < 30_000, "Should compile 50 workers in <30s (was ${duration}ms)")
    }

    private fun prepareCompilation(vararg sourceFiles: SourceFile): KotlinCompilation {
        return KotlinCompilation().apply {
            sources = sourceFiles.toList()
            symbolProcessorProviders = listOf(WorkerProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
    }

    private fun KotlinCompilation.Result.kspGeneratedFile(fileName: String): File {
        val kspGeneratedDir = File(outputDirectory.parentFile, "ksp/sources/kotlin")
        return kspGeneratedDir.walkTopDown()
            .first { it.name == fileName }
    }
}
