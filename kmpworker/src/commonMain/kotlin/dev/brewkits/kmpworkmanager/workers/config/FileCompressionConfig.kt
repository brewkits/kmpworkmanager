package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Compression level for ZIP archives.
 */
@Serializable
enum class CompressionLevel {
    LOW,    // Fast compression, larger file size
    MEDIUM, // Balanced
    HIGH;   // Slower compression, smaller file size

    companion object {
        fun fromString(level: String): CompressionLevel {
            return when (level.lowercase()) {
                "low" -> LOW
                "medium" -> MEDIUM
                "high" -> HIGH
                else -> throw IllegalArgumentException("Unsupported compression level: $level")
            }
        }
    }
}

/**
 * Configuration for FileCompressionWorker.
 *
 * @property inputPath Absolute path to file or directory to compress
 * @property outputPath Absolute path for the output ZIP file
 * @property compressionLevel Compression level (low, medium, high) - default: medium
 * @property excludePatterns List of patterns to exclude (e.g., "*.tmp", ".DS_Store")
 * @property deleteOriginal Whether to delete original files after compression - default: false
 */
@Serializable
data class FileCompressionConfig(
    val inputPath: String,
    val outputPath: String,
    val compressionLevel: String = "medium",
    val excludePatterns: List<String>? = null,
    val deleteOriginal: Boolean = false
) {
    val level: CompressionLevel
        get() = CompressionLevel.fromString(compressionLevel)

    init {
        require(inputPath.isNotBlank()) {
            "Input path cannot be blank"
        }
        require(outputPath.isNotBlank()) {
            "Output path cannot be blank"
        }
        require(outputPath.endsWith(".zip", ignoreCase = true)) {
            "Output path must end with .zip"
        }
    }
}
