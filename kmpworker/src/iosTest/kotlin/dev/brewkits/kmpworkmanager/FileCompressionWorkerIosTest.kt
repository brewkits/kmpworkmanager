@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSData
import kotlin.test.*

/**
 * iOS FileCompressionWorker honesty tests.
 *
 * The iOS implementation has no real ZIP codec. These tests pin down the
 * contract introduced for the v2.5 honesty refactor:
 * - Default: fails fast with an explicit error (caller cannot accidentally ship un-zipped data).
 * - Opt-in: copies the file uncompressed and labels the result accordingly.
 */
class FileCompressionWorkerIosTest {

    private val fileManager = NSFileManager.defaultManager

    private fun tempPath(suffix: String): String {
        val base = NSTemporaryDirectory()
        return "$base/kmp-fcw-${platform.Foundation.NSUUID().UUIDString()}-$suffix"
    }

    private fun writeText(path: String, contents: String) {
        val ns = NSString.create(string = contents)
        val data = ns.dataUsingEncoding(NSUTF8StringEncoding) as NSData
        fileManager.createFileAtPath(path, contents = data, attributes = null)
    }

    @Test
    fun defaultFailsWithoutOptIn() = runTest {
        val input = tempPath("in.txt")
        val output = tempPath("out.zip")
        writeText(input, "hello world")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(inputPath = input, outputPath = output)
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure, "Expected Failure when fallback not opted in")
        val message = (result as WorkerResult.Failure).message
        assertTrue(
            message.contains("not implemented on iOS", ignoreCase = true) ||
                message.contains("allowIosUncompressedFallback", ignoreCase = true),
            "Failure message should explain the fallback opt-in: $message"
        )
        assertFalse(fileManager.fileExistsAtPath(output), "No output file should be produced")
    }

    @Test
    fun optInProducesCopyAndLabelsIt() = runTest {
        val input = tempPath("in.txt")
        val output = tempPath("out.zip")
        writeText(input, "hello world")

        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(
                inputPath = input,
                outputPath = output,
                allowIosUncompressedFallback = true
            )
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Success, "Expected Success with fallback opt-in")
        val message = (result as WorkerResult.Success).message ?: ""
        assertTrue(
            message.contains("NOT compressed", ignoreCase = true),
            "Success message must label the output as uncompressed: $message"
        )
        assertTrue(fileManager.fileExistsAtPath(output), "Output copy must exist")
    }

    @Test
    fun missingInputAlwaysFails() = runTest {
        val output = tempPath("out.zip")
        val worker = FileCompressionWorker()
        val configJson = Json.encodeToString(
            FileCompressionConfig.serializer(),
            FileCompressionConfig(
                inputPath = "/does/not/exist/${platform.Foundation.NSUUID().UUIDString()}",
                outputPath = output,
                allowIosUncompressedFallback = true
            )
        )

        val result = worker.doWork(configJson, WorkerEnvironment(null) { false })

        assertTrue(result is WorkerResult.Failure)
    }
}
