package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.cinterop.ExperimentalForeignApi

import platform.Foundation.NSFileManager
import platform.Foundation.NSError
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.ObjCObjectVar

/**
 * iOS implementation of file compression.
 *
 * **Status: NOT a real ZIP implementation.**
 * Kotlin/Native does not ship a ZIP codec. Real iOS support requires integrating
 * [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) via cinterop (or any
 * other Swift/ObjC ZIP library). Until that is wired in, this worker can only
 * copy the input file uncompressed — which is almost certainly not what the
 * caller wants for a backup / upload pipeline.
 *
 * **Default behavior (safe):** return `WorkerResult.Failure` so the caller is
 * forced to opt-in to the fallback. This is the right default for production —
 * silently shipping un-zipped media to a server has caused enough surprises.
 *
 * **Opt-in fallback (for demos, dev builds, and chains that just need a file
 * present at `outputPath`):** set [FileCompressionConfig.allowIosUncompressedFallback]
 * to `true`. The worker will log a loud warning and copy the file. The output is
 * NOT a valid ZIP — name it accordingly.
 *
 * To add real ZIP support:
 * 1. Add ZIPFoundation to your iOS project via Swift Package Manager or CocoaPods
 * 2. Create a cinterop definition to expose it to Kotlin
 * 3. Replace this implementation with actual ZIPFoundation calls
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual suspend fun platformCompress(config: FileCompressionConfig): WorkerResult {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(config.inputPath)) {
        return WorkerResult.Failure("Input file does not exist: ${config.inputPath}")
    }

    if (!config.allowIosUncompressedFallback) {
        val msg = "FileCompressionWorker is not implemented on iOS. " +
            "Kotlin/Native has no built-in ZIP codec — integrate ZIPFoundation via cinterop, " +
            "or set FileCompressionConfig.allowIosUncompressedFallback = true to accept an " +
            "uncompressed copy (NOT a real ZIP). See docs/BUILTIN_WORKERS_GUIDE.md."
        Logger.e("FileCompressionWorker", msg)
        return WorkerResult.Failure(msg)
    }

    Logger.w(
        "FileCompressionWorker",
        "⚠️ iOS fallback is enabled — output at ${config.outputPath} will be an UNCOMPRESSED COPY " +
            "of ${config.inputPath}, not a real ZIP archive. Integrate ZIPFoundation for real compression."
    )

    return memScoped {
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()

        if (fileManager.fileExistsAtPath(config.outputPath)) {
            fileManager.removeItemAtPath(config.outputPath, null)
        }

        val success = fileManager.copyItemAtPath(config.inputPath, config.outputPath, errorPtr.ptr)

        if (success) {
            WorkerResult.Success(message = "iOS fallback copy (NOT compressed). Output: ${config.outputPath}")
        } else {
            val error = errorPtr.value
            WorkerResult.Failure("iOS fallback copy failed: ${error?.localizedDescription}")
        }
    }
}
