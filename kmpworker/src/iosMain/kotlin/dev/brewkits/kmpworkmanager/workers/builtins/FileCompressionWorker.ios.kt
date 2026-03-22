package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.cinterop.ExperimentalForeignApi

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
 * This function returns Failure explicitly so callers are aware of the limitation
 * instead of silently producing an uncompressed copy.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun platformCompress(config: FileCompressionConfig): WorkerResult {
    Logger.e(
        "FileCompressionWorker",
        "ZIP compression is not natively available on iOS via Kotlin/Native. " +
            "Integrate ZIPFoundation via cinterop to enable this feature. " +
            "See: https://github.com/weichsel/ZIPFoundation"
    )
    return WorkerResult.Failure(
        "FileCompressionWorker is not supported on iOS. " +
            "Integrate ZIPFoundation via cinterop to enable ZIP compression."
    )
}
