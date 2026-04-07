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
     * - Strips RFC 3986 UserInfo to prevent `evil.com@127.0.0.1` bypasses
     *
     * **DNS Rebinding — known library-layer limitation:**
     * This validator checks the URL hostname at call time. It cannot defend against DNS
     * rebinding attacks, where an attacker-controlled domain initially resolves to a public IP
     * (passing this check) and later re-resolves to a private IP at connection time.
     *
     * Consumers operating in high-trust environments (e.g. server-side schedulers) should
     * add network-layer defences: certificate pinning, a custom DNS resolver that blocks
     * private ranges, or an egress proxy with its own IP blocklist.
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
     * @return The hostname to evaluate, or null if parsing fails or any candidate is blocked
     */
    private fun extractHostname(url: String): String? {
        return try {
            // Remove scheme
            val withoutScheme = url.substringAfter("://", "")
            if (withoutScheme.isEmpty() || withoutScheme.startsWith("/")) return null

            // Remove path, query, fragment
            val authority = withoutScheme.substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")

            if (authority.isBlank()) return null

            // Multi-@ defence: RFC 3986 allows at most one '@' in the authority (userinfo@host).
            // A URL like `https://user:pass@evil.com:80@legit.com` is malformed and different
            // HTTP clients may resolve it to different hosts. To prevent ambiguity-based SSRF
            // bypasses, extract ALL potential host segments (every part after an '@') and
            // return null (reject) if any of them resolves to a blocked hostname.
            val atCount = authority.count { it == '@' }
            if (atCount > 1) {
                // Check every segment after an '@' as a potential host target.
                val candidates = authority.split("@").drop(1) // skip userinfo prefix
                for (candidate in candidates) {
                    val h = stripPort(candidate) ?: return null
                    if (isBlockedHostname(h)) return null
                }
                // All candidates passed — return the last one (what RFC-compliant clients use).
                return stripPort(candidates.last())
            }

            // Strip RFC 3986 UserInfo (e.g. "user:pass@host" or "evil.com@127.0.0.1" → "127.0.0.1").
            // Must happen before port/hostname splitting: without this, the full "evil.com@127.0.0.1"
            // string fails looksLikeIPv4() (contains non-digit chars), so the private-IP check is
            // never reached and the URL is allowed — SSRF bypass.
            val strippedUserInfo = authority.substringAfterLast("@")

            stripPort(strippedUserInfo)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Strips the port from a host:port string and returns the lowercase hostname.
     * Returns null for malformed input (unbracketed IPv6, blank).
     */
    private fun stripPort(hostWithPort: String): String? {
        val hostname = if (hostWithPort.startsWith("[")) {
            // IPv6 address like [::1]:8080
            hostWithPort.substringAfter("[").substringBefore("]")
        } else {
            // Check if it looks like an IPv6 address (multiple colons)
            val colonCount = hostWithPort.count { it == ':' }
            if (colonCount >= 2) {
                // Unbracketed IPv6 — RFC 2732 requires brackets for IPv6 in URLs.
                // Any unbracketed multi-colon hostname is malformed; return null to reject.
                return null
            } else {
                hostWithPort.substringBefore(":")
            }
        }
        if (hostname.isBlank()) return null
        return hostname.lowercase()
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

        // Cloud metadata endpoints (AWS, GCP, Azure, Alibaba Cloud)
        if (hostname == "169.254.169.254" ||   // AWS / GCP / Azure instance metadata
            hostname == "fd00:ec2::254" ||       // AWS IPv6 metadata
            hostname == "100.100.100.200" ||     // Alibaba Cloud ECS metadata
            hostname == "metadata.google.internal") {
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
     * Checks performed:
     * - Blank / empty path
     * - Literal `..` segments
     * - URL-encoded `..` variants: `%2e%2e`, `%252e%252e` (double-encoded)
     * - Backslash normalisation (Windows-style separators)
     * - Null-byte injection (`\u0000`)
     * - Absolute paths targeting sensitive system directories
     *
     * @param path The file path to validate
     * @return true if path is safe, false if it contains a suspicious pattern
     */
    fun validateFilePath(path: String): Boolean {
        if (path.isBlank()) return false

        // Normalise to a canonical form before checking, covering the most common
        // bypass techniques without requiring platform-specific APIs.
        // lowercase() first ensures all percent-encoded sequences are case-folded once,
        // so subsequent replacements don't need ignoreCase = true and can't be bypassed
        // by mixed-case encodings like %2E or %5C.
        val normalised = path
            .lowercase()                       // Case-fold everything (handles %2E, %5C, etc.)
            .replace("\\", "/")                // Windows separators
            .replace("\u0000", "")             // Null-byte injection
            .replace("%252e", ".")             // Double URL-encoded dot
            .replace("%2e", ".")               // URL-encoded dot
            .replace("%2f", "/")               // URL-encoded slash
            .replace("%5c", "/")               // URL-encoded backslash

        if (normalised.contains("..")) return false

        // Block absolute paths that target sensitive OS directories.
        // This is a defence-in-depth measure: the storage sandbox should be the
        // primary enforcement point, but this catches misconfigured callers early.
        val sensitiveRoots = listOf("/etc", "/proc", "/sys", "/dev", "/private/etc")
        if (sensitiveRoots.any { normalised.startsWith(it) }) return false

        return true
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
