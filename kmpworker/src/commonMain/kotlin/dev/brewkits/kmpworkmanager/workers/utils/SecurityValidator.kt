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
     * Validates that a URL uses http:// or https:// scheme and protects against SSRF attacks.
     *
     * SSRF Protection:
     * - Blocks localhost (localhost, 127.0.0.1, [::1])
     * - Blocks private networks (10.x.x.x, 172.16-31.x.x, 192.168.x.x, fc00::/7, fe80::/10)
     * - Blocks cloud metadata endpoints (169.254.169.254, fd00:ec2::254)
     * - Blocks link-local addresses
     * - Only allows http:// and https:// schemes
     *
     * @param url The URL string to validate
     * @return true if URL is valid and safe, false otherwise
     */
    fun validateURL(url: String): Boolean {
        // Check scheme
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)) {
            return false
        }

        // Extract hostname from URL
        val hostname = extractHostname(url) ?: return false

        // SSRF Protection checks
        if (isBlockedHostname(hostname)) {
            return false
        }

        return true
    }

    /**
     * Extracts hostname from URL.
     * Examples:
     * - "https://example.com/path" -> "example.com"
     * - "http://192.168.1.1:8080/api" -> "192.168.1.1"
     *
     * @param url The URL to parse
     * @return The hostname, or null if parsing fails
     */
    private fun extractHostname(url: String): String? {
        return try {
            // Remove scheme
            val withoutScheme = url.substringAfter("://", "")
            if (withoutScheme.isEmpty() || withoutScheme.startsWith("/")) return null

            // Remove path, query, fragment
            val hostnameWithPort = withoutScheme.substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")

            // Check if we have a valid hostname
            if (hostnameWithPort.isBlank()) return null

            // Remove port if present
            val hostname = if (hostnameWithPort.startsWith("[")) {
                // IPv6 address like [::1]:8080
                hostnameWithPort.substringAfter("[").substringBefore("]")
            } else {
                // Check if it looks like an IPv6 address (multiple colons)
                val colonCount = hostnameWithPort.count { it == ':' }
                if (colonCount >= 2) {
                    // Malformed IPv6 without brackets (e.g., 0:0:0:0:0:0:0:1)
                    // Return the whole thing for validation
                    hostnameWithPort
                } else {
                    // Regular hostname or IPv4 with port
                    hostnameWithPort.substringBefore(":")
                }
            }

            // Final validation: hostname must not be empty
            if (hostname.isBlank()) return null

            hostname.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if hostname should be blocked for SSRF protection.
     *
     * @param hostname The hostname to check (must be lowercase)
     * @return true if hostname should be blocked
     */
    private fun isBlockedHostname(hostname: String): Boolean {
        // Localhost variations
        if (hostname in listOf("localhost", "127.0.0.1", "::1", "0.0.0.0")) {
            return true
        }

        // AWS/Cloud metadata endpoints
        if (hostname == "169.254.169.254" || hostname == "fd00:ec2::254") {
            return true
        }

        // If it looks like an IPv4 address, validate it
        if (looksLikeIPv4(hostname)) {
            // Block if it's private OR invalid
            return isPrivateIPv4(hostname) || !isValidIPv4(hostname)
        }

        // Check for private IPv6 ranges
        if (isPrivateIPv6(hostname)) {
            return true
        }

        // Check for localhost-like hostnames
        if (hostname.endsWith(".localhost") || hostname.endsWith(".local")) {
            return true
        }

        return false
    }

    /**
     * Checks if a string looks like an IPv4 address (has dots and numbers).
     */
    private fun looksLikeIPv4(hostname: String): Boolean {
        return hostname.contains(".") && hostname.all { it.isDigit() || it == '.' }
    }

    /**
     * Validates if a string is a valid IPv4 address.
     * - Must have exactly 4 octets
     * - Each octet must be 0-255
     */
    private fun isValidIPv4(hostname: String): Boolean {
        val parts = hostname.split(".")
        if (parts.size != 4) return false

        return try {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            octets.all { it in 0..255 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if hostname is a private IPv4 address.
     * Private ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
     *
     * @param hostname The hostname to check
     * @return true if it's a private IPv4 address
     */
    private fun isPrivateIPv4(hostname: String): Boolean {
        val parts = hostname.split(".")
        if (parts.size != 4) return false

        return try {
            val octets = parts.map { it.toInt() }
            if (octets.any { it < 0 || it > 255 }) return false

            when {
                // Loopback 127.0.0.0/8
                octets[0] == 127 -> true
                // 10.0.0.0/8
                octets[0] == 10 -> true
                // 172.16.0.0/12
                octets[0] == 172 && octets[1] in 16..31 -> true
                // 192.168.0.0/16
                octets[0] == 192 && octets[1] == 168 -> true
                // Link-local 169.254.0.0/16
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if hostname is a private IPv6 address.
     * Private ranges: fc00::/7 (ULA), fe80::/10 (link-local)
     *
     * @param hostname The hostname to check
     * @return true if it's a private IPv6 address
     */
    private fun isPrivateIPv6(hostname: String): Boolean {
        if (!hostname.contains(":")) return false

        return try {
            // Simplified check for common private IPv6 patterns
            when {
                // ULA (Unique Local Address) - fc00::/7
                hostname.startsWith("fc") || hostname.startsWith("fd") -> true
                // Link-local - fe80::/10
                hostname.startsWith("fe80") || hostname.startsWith("fe8") ||
                hostname.startsWith("fe9") || hostname.startsWith("fea") ||
                hostname.startsWith("feb") -> true
                // Loopback ::1 (compressed form)
                hostname == "::1" -> true
                // Loopback 0:0:0:0:0:0:0:1 (expanded form)
                isIPv6Loopback(hostname) -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if an IPv6 address is the loopback address (::1 or its expanded forms).
     * Handles: ::1, 0:0:0:0:0:0:0:1, 0000:0000:0000:0000:0000:0000:0000:0001, etc.
     */
    private fun isIPv6Loopback(hostname: String): Boolean {
        val parts = hostname.split(":")
        // IPv6 should have 8 segments for full form
        if (parts.size != 8) return false

        return try {
            // Check if all segments are 0 except the last one which should be 1
            val allZeros = parts.take(7).all { it.toIntOrNull(16) == 0 }
            val lastIsOne = parts.last().toIntOrNull(16) == 1
            allZeros && lastIsOne
        } catch (e: Exception) {
            false
        }
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
            "${string.substring(0, maxLength)}..."
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
