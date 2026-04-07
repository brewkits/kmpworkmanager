package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig

/**
 * Built-in worker for compressing files and directories into ZIP archives.
 *
 * Features:
 * - Recursive directory compression
 * - Three compression levels: low (fast), medium (balanced), high (best compression)
 * - Exclude patterns support (*.tmp, .DS_Store, etc.)
 * - Optional deletion of original files after compression
 * - Compression statistics logging
 *
 * **Platform Support:**
 * - Android: Uses java.util.zip.ZipOutputStream
 * - iOS: Uses platform ZIP APIs
 *
 * **Configuration Example:**
 * ```json
 * {
 *   "inputPath": "/path/to/file/or/directory",
 *   "outputPath": "/path/to/output.zip",
 *   "compressionLevel": "medium",
 *   "excludePatterns": ["*.tmp", ".DS_Store", "*.log"],
 *   "deleteOriginal": false
 * }
 * ```
 */
class FileCompressionWorker : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        Logger.i("FileCompressionWorker", "Starting file compression worker...")

        if (input == null) {
            Logger.e("FileCompressionWorker", "Input configuration is null")
            return WorkerResult.Failure("Input configuration is null")
        }

        return try {
            val config = KmpWorkManagerRuntime.json.decodeFromString<FileCompressionConfig>(input)
            Logger.i("FileCompressionWorker", "Compressing ${config.inputPath} to ${config.outputPath}")

            platformCompress(config)
        } catch (e: Exception) {
            Logger.e("FileCompressionWorker", "Failed to compress file", e)
            WorkerResult.Failure("Compression failed: ${e.message}")
        }
    }
}

/**
 * Platform-specific compression function.
 * Must be implemented in androidMain and iosMain source sets.
 *
 * Returns WorkerResult with compression statistics
 *
 * @param config Compression configuration
 * @return WorkerResult with compression details (original size, compressed size, compression ratio)
 */
internal expect suspend fun platformCompress(config: FileCompressionConfig): WorkerResult
