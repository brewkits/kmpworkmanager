package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.serialization.json.Json

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
 *
 * **Usage:**
 * ```kotlin
 * val config = Json.encodeToString(FileCompressionConfig.serializer(), FileCompressionConfig(
 *     inputPath = "/storage/logs",
 *     outputPath = "/storage/logs_backup.zip",
 *     compressionLevel = "high",
 *     excludePatterns = listOf("*.tmp", ".DS_Store")
 * ))
 *
 * scheduler.enqueue(
 *     id = "compress-logs",
 *     trigger = TaskTrigger.OneTime(),
 *     workerClassName = "FileCompressionWorker",
 *     inputJson = config
 * )
 * ```
 */
class FileCompressionWorker : Worker {

    override suspend fun doWork(input: String?): Boolean {
        Logger.i("FileCompressionWorker", "Starting file compression worker...")

        if (input == null) {
            Logger.e("FileCompressionWorker", "Input configuration is null")
            return false
        }

        return try {
            val config = Json.decodeFromString<FileCompressionConfig>(input)
            Logger.i("FileCompressionWorker", "Compressing ${config.inputPath} to ${config.outputPath}")

            compressFile(config)
        } catch (e: Exception) {
            Logger.e("FileCompressionWorker", "Failed to compress file", e)
            false
        }
    }

    /**
     * Platform-specific compression implementation.
     * Implemented in androidMain and iosMain source sets.
     */
    private suspend fun compressFile(config: FileCompressionConfig): Boolean {
        return platformCompress(config)
    }
}

/**
 * Platform-specific compression function.
 * Must be implemented in androidMain and iosMain source sets.
 *
 * @param config Compression configuration
 * @return true if compression succeeded, false otherwise
 */
internal expect suspend fun platformCompress(config: FileCompressionConfig): Boolean
