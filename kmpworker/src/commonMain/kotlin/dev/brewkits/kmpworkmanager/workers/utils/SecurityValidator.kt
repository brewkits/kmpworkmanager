package dev.brewkits.kmpworkmanager.workers.utils

/**
 * Security validation utilities for built-in workers.
 *
 * Provides centralized validation for:
 * - URL schemes (http/https only)
 * - File path validation
 * - Request/response size limits
 * - Safe logging (truncation, redaction)
 */
object SecurityValidator {
    const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024      // 10MB
    const val MAX_RESPONSE_BODY_SIZE = 50 * 1024 * 1024     // 50MB

    /**
     * Validates that a URL uses http:// or https:// scheme.
     *
     * @param url The URL string to validate
     * @return true if URL is valid, false otherwise
     */
    fun validateURL(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
               url.startsWith("https://", ignoreCase = true)
    }

    /**
     * Validates that a file path doesn't contain path traversal attempts.
     *
     * @param path The file path to validate
     * @return true if path is safe, false if it contains ".." or other suspicious patterns
     */
    fun validateFilePath(path: String): Boolean {
        // Check for path traversal attempts
        return !path.contains("..") && path.isNotBlank()
    }

    /**
     * Redacts query parameters from URL for safe logging.
     * Example: "https://api.com/data?key=secret" -> "https://api.com/data?[REDACTED]"
     *
     * @param url The URL to sanitize
     * @return Sanitized URL safe for logging
     */
    fun sanitizedURL(url: String): String {
        val queryIndex = url.indexOf('?')
        return if (queryIndex != -1) {
            "${url.substring(0, queryIndex)}?[REDACTED]"
        } else {
            url
        }
    }

    /**
     * Truncates a string for safe logging.
     *
     * @param string The string to truncate
     * @param maxLength Maximum length (default: 200 characters)
     * @return Truncated string
     */
    fun truncateForLogging(string: String, maxLength: Int = 200): String {
        return if (string.length <= maxLength) {
            string
        } else {
            "${string.substring(0, maxLength)}... [truncated ${string.length - maxLength} chars]"
        }
    }

    /**
     * Validates request body size doesn't exceed the limit.
     *
     * @param data The data to validate
     * @return true if size is acceptable
     */
    fun validateRequestSize(data: ByteArray): Boolean {
        return data.size <= MAX_REQUEST_BODY_SIZE
    }

    /**
     * Validates response body size doesn't exceed the limit.
     *
     * @param data The data to validate
     * @return true if size is acceptable
     */
    fun validateResponseSize(data: ByteArray): Boolean {
        return data.size <= MAX_RESPONSE_BODY_SIZE
    }

    /**
     * Formats byte size for human-readable output.
     *
     * @param bytes The size in bytes
     * @return Formatted string (e.g., "1.5 MB", "512 KB")
     */
    fun formatByteSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> {
                val kb = bytes / 1024.0
                "${(kb * 10).toLong() / 10.0} KB"
            }
            bytes < 1024 * 1024 * 1024 -> {
                val mb = bytes / (1024.0 * 1024.0)
                "${(mb * 10).toLong() / 10.0} MB"
            }
            else -> {
                val gb = bytes / (1024.0 * 1024.0 * 1024.0)
                "${(gb * 10).toLong() / 10.0} GB"
            }
        }
    }
}
