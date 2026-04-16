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
 * **Not implemented**: Native ZIP compression on iOS/Kotlin requires integrating
 * ZIPFoundation (https://github.com/weichsel/ZIPFoundation) via cinterop.
 *
 * To add real ZIP support:
 * 1. Add ZIPFoundation to your iOS project via Swift Package Manager or CocoaPods
 * 2. Create a cinterop definition to expose it to Kotlin
 * 3. Replace this implementation with actual ZIPFoundation calls
 *
 * For the demo, this creates a dummy (uncompressed) copy of the input file
 * at the output path so that chained demos can proceed.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual suspend fun platformCompress(config: FileCompressionConfig): WorkerResult {
    Logger.w(
        "FileCompressionWorker",
        "ZIP compression is not natively available on iOS via Kotlin/Native. " +
            "Creating a dummy uncompressed copy to simulate completion for demo chains. " +
            "Integrate ZIPFoundation via cinterop to enable full feature."
    )
    
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(config.inputPath)) {
        return WorkerResult.Failure("Input file does not exist: ${config.inputPath}")
    }
    
    return memScoped {
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        
        // Remove existing file if present
        if (fileManager.fileExistsAtPath(config.outputPath)) {
            fileManager.removeItemAtPath(config.outputPath, null)
        }
        
        val success = fileManager.copyItemAtPath(config.inputPath, config.outputPath, errorPtr.ptr)
        
        if (success) {
            WorkerResult.Success()
        } else {
            val error = errorPtr.value
            WorkerResult.Failure("Wait, dummy compression copy failed: ${error?.localizedDescription}")
        }
    }
}
