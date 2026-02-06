package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.CompressionLevel
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android implementation of file compression using java.util.zip.
 */
internal actual suspend fun platformCompress(config: FileCompressionConfig): Boolean {
    val inputFile = File(config.inputPath)
    val outputFile = File(config.outputPath)

    if (!inputFile.exists()) {
        Logger.e("FileCompressionWorker", "Input file/directory does not exist: ${config.inputPath}")
        return false
    }

    // Validate output path
    if (!SecurityValidator.validateFilePath(config.outputPath)) {
        Logger.e("FileCompressionWorker", "Invalid output path: ${config.outputPath}")
        return false
    }

    // Create parent directory if needed
    outputFile.parentFile?.let { parent ->
        if (!parent.exists()) {
            parent.mkdirs()
            Logger.d("FileCompressionWorker", "Created parent directory: ${parent.absolutePath}")
        }
    }

    return try {
        val originalSize: Long
        val zipCompressionLevel = when (config.level) {
            CompressionLevel.LOW -> 3
            CompressionLevel.MEDIUM -> 6
            CompressionLevel.HIGH -> 9
        }

        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            zipOut.setLevel(zipCompressionLevel)

            if (inputFile.isDirectory) {
                originalSize = compressDirectory(
                    zipOut,
                    inputFile,
                    inputFile.name,
                    config.excludePatterns ?: emptyList()
                )
            } else {
                originalSize = inputFile.length()
                compressFile(zipOut, inputFile, inputFile.name)
            }

            zipOut.finish()
        }

        val compressedSize = outputFile.length()
        val compressionRatio = if (originalSize > 0) {
            ((compressedSize.toDouble() / originalSize.toDouble()) * 100).toInt()
        } else {
            0
        }

        Logger.i(
            "FileCompressionWorker",
            "Compression complete - Original: ${SecurityValidator.formatByteSize(originalSize)}, " +
                    "Compressed: ${SecurityValidator.formatByteSize(compressedSize)}, " +
                    "Ratio: $compressionRatio%"
        )

        // Delete original if requested
        if (config.deleteOriginal) {
            if (inputFile.deleteRecursively()) {
                Logger.i("FileCompressionWorker", "Deleted original file/directory: ${config.inputPath}")
            } else {
                Logger.w("FileCompressionWorker", "Failed to delete original file/directory")
            }
        }

        true
    } catch (e: Exception) {
        Logger.e("FileCompressionWorker", "Compression failed", e)
        // Cleanup partial zip file
        if (outputFile.exists()) {
            outputFile.delete()
        }
        false
    }
}

/**
 * Compresses a directory recursively.
 */
private fun compressDirectory(
    zipOut: ZipOutputStream,
    directory: File,
    baseName: String,
    excludePatterns: List<String>
): Long {
    var totalSize = 0L
    val files = directory.listFiles() ?: return 0L

    for (file in files) {
        val entryName = "$baseName/${file.name}"

        // Check if file should be excluded
        if (shouldExclude(file.name, excludePatterns)) {
            Logger.d("FileCompressionWorker", "Excluded: $entryName")
            continue
        }

        if (file.isDirectory) {
            totalSize += compressDirectory(zipOut, file, entryName, excludePatterns)
        } else {
            totalSize += file.length()
            compressFile(zipOut, file, entryName)
        }
    }

    return totalSize
}

/**
 * Compresses a single file into the ZIP stream.
 */
private fun compressFile(zipOut: ZipOutputStream, file: File, entryName: String) {
    BufferedInputStream(FileInputStream(file), 8192).use { bis ->
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zipOut.putNextEntry(entry)

        val buffer = ByteArray(8192)
        var length: Int
        while (bis.read(buffer).also { length = it } != -1) {
            zipOut.write(buffer, 0, length)
        }

        zipOut.closeEntry()
    }
}

/**
 * Checks if a file name matches any of the exclude patterns.
 */
private fun shouldExclude(fileName: String, patterns: List<String>): Boolean {
    for (pattern in patterns) {
        when {
            // Extension pattern: *.tmp
            pattern.startsWith("*.") -> {
                val extension = pattern.substring(1)
                if (fileName.endsWith(extension, ignoreCase = true)) {
                    return true
                }
            }
            // Suffix pattern: *backup
            pattern.startsWith("*") -> {
                val suffix = pattern.substring(1)
                if (fileName.endsWith(suffix, ignoreCase = true)) {
                    return true
                }
            }
            // Prefix pattern: temp*
            pattern.endsWith("*") -> {
                val prefix = pattern.substring(0, pattern.length - 1)
                if (fileName.startsWith(prefix, ignoreCase = true)) {
                    return true
                }
            }
            // Exact match
            else -> {
                if (fileName.equals(pattern, ignoreCase = true)) {
                    return true
                }
            }
        }
    }
    return false
}
