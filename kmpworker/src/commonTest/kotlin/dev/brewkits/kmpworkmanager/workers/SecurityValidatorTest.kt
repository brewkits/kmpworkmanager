package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SecurityValidator SSRF protection.
 */
class SecurityValidatorTest {

    @Test
    fun testValidURLs_shouldPass() {
        // Public domains
        assertTrue(SecurityValidator.validateURL("https://api.example.com/data"))
        assertTrue(SecurityValidator.validateURL("http://public-api.com/endpoint"))
        assertTrue(SecurityValidator.validateURL("https://github.com/user/repo"))
        assertTrue(SecurityValidator.validateURL("http://8.8.8.8/test"))
        assertTrue(SecurityValidator.validateURL("https://1.1.1.1:443/api"))
    }

    @Test
    fun testInvalidSchemes_shouldFail() {
        assertFalse(SecurityValidator.validateURL("ftp://example.com"))
        assertFalse(SecurityValidator.validateURL("file:///etc/passwd"))
        assertFalse(SecurityValidator.validateURL("data:text/html,<script>alert(1)</script>"))
        assertFalse(SecurityValidator.validateURL("javascript:alert(1)"))
        assertFalse(SecurityValidator.validateURL("example.com"))
        assertFalse(SecurityValidator.validateURL("//example.com"))
    }

    @Test
    fun testLocalhostVariations_shouldBeBlocked() {
        // Standard localhost
        assertFalse(SecurityValidator.validateURL("http://localhost/api"))
        assertFalse(SecurityValidator.validateURL("https://localhost:8080/admin"))
        assertFalse(SecurityValidator.validateURL("http://LOCALHOST/test"))

        // IP loopback
        assertFalse(SecurityValidator.validateURL("http://127.0.0.1/api"))
        assertFalse(SecurityValidator.validateURL("http://127.0.0.1:8080/"))
        assertFalse(SecurityValidator.validateURL("http://127.1.1.1/"))

        // IPv6 loopback
        assertFalse(SecurityValidator.validateURL("http://[::1]/api"))
        assertFalse(SecurityValidator.validateURL("http://[::1]:8080/"))

        // 0.0.0.0
        assertFalse(SecurityValidator.validateURL("http://0.0.0.0/"))
    }

    @Test
    fun testPrivateIPv4Ranges_shouldBeBlocked() {
        // 10.0.0.0/8
        assertFalse(SecurityValidator.validateURL("http://10.0.0.1/"))
        assertFalse(SecurityValidator.validateURL("http://10.1.1.1/api"))
        assertFalse(SecurityValidator.validateURL("http://10.255.255.255:8080/"))

        // 172.16.0.0/12
        assertFalse(SecurityValidator.validateURL("http://172.16.0.1/"))
        assertFalse(SecurityValidator.validateURL("http://172.20.10.5/api"))
        assertFalse(SecurityValidator.validateURL("http://172.31.255.255/"))

        // 192.168.0.0/16
        assertFalse(SecurityValidator.validateURL("http://192.168.0.1/"))
        assertFalse(SecurityValidator.validateURL("http://192.168.1.1/router"))
        assertFalse(SecurityValidator.validateURL("http://192.168.255.255:8080/"))
    }

    @Test
    fun testCloudMetadataEndpoints_shouldBeBlocked() {
        // AWS metadata
        assertFalse(SecurityValidator.validateURL("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SecurityValidator.validateURL("http://169.254.169.254:80/"))

        // IPv6 AWS metadata
        assertFalse(SecurityValidator.validateURL("http://[fd00:ec2::254]/"))
    }

    @Test
    fun testLinkLocalAddresses_shouldBeBlocked() {
        // 169.254.0.0/16
        assertFalse(SecurityValidator.validateURL("http://169.254.1.1/"))
        assertFalse(SecurityValidator.validateURL("http://169.254.100.200/api"))
    }

    @Test
    fun testPrivateIPv6Ranges_shouldBeBlocked() {
        // ULA (Unique Local Address) - fc00::/7
        assertFalse(SecurityValidator.validateURL("http://[fc00::1]/"))
        assertFalse(SecurityValidator.validateURL("http://[fd12:3456:789a::1]/api"))

        // Link-local - fe80::/10
        assertFalse(SecurityValidator.validateURL("http://[fe80::1]/"))
        assertFalse(SecurityValidator.validateURL("http://[fe80::dead:beef]/"))
    }

    @Test
    fun testLocalHostnames_shouldBeBlocked() {
        assertFalse(SecurityValidator.validateURL("http://myapp.localhost/"))
        assertFalse(SecurityValidator.validateURL("http://test.local/api"))
    }

    @Test
    fun testSSRF_UserInfoBypass_shouldBeBlocked() {
        // RFC 3986 UserInfo bypass: browser/HTTP client connects to the host AFTER '@',
        // but a naive parser may use the part BEFORE '@' for hostname validation.
        assertFalse(SecurityValidator.validateURL("https://evil.com@127.0.0.1/"))
        assertFalse(SecurityValidator.validateURL("https://evil.com@127.0.0.1:8080/admin"))
        assertFalse(SecurityValidator.validateURL("http://attacker@10.0.0.1/internal"))
        assertFalse(SecurityValidator.validateURL("http://x@192.168.1.1/"))
        assertFalse(SecurityValidator.validateURL("http://user:pass@169.254.169.254/latest/meta-data/"))
        assertFalse(SecurityValidator.validateURL("https://good.example.com@localhost/"))
        // Public host after @ should still pass
        assertTrue(SecurityValidator.validateURL("https://user@api.example.com/data"))
    }

    @Test
    fun testEdgeCases_malformedURLs() {
        // Malformed URLs should fail
        assertFalse(SecurityValidator.validateURL("http://"))
        assertFalse(SecurityValidator.validateURL("https://"))
        assertFalse(SecurityValidator.validateURL("http:///path"))
        assertFalse(SecurityValidator.validateURL(""))
    }

    @Test
    fun testURLsWithPaths_shouldExtractHostnameCorrectly() {
        // Valid public URLs with paths
        assertTrue(SecurityValidator.validateURL("https://api.example.com/v1/users?id=123"))
        assertTrue(SecurityValidator.validateURL("http://cdn.example.com/static/image.png#anchor"))

        // Blocked URLs with paths
        assertFalse(SecurityValidator.validateURL("http://localhost/api/v1/admin"))
        assertFalse(SecurityValidator.validateURL("http://10.0.0.1:8080/internal/api"))
    }

    @Test
    fun testIPv4EdgeCases() {
        // Invalid octets should be handled gracefully
        assertFalse(SecurityValidator.validateURL("http://256.1.1.1/"))
        assertFalse(SecurityValidator.validateURL("http://1.1.1/"))
        assertFalse(SecurityValidator.validateURL("http://1.1.1.1.1/"))
    }

    @Test
    fun testPublicIPRanges_shouldNotBeBlocked() {
        // Common public DNS servers
        assertTrue(SecurityValidator.validateURL("http://8.8.8.8/"))  // Google DNS
        assertTrue(SecurityValidator.validateURL("http://1.1.1.1/"))  // Cloudflare DNS
        assertTrue(SecurityValidator.validateURL("http://208.67.222.222/"))  // OpenDNS

        // Random public IPs
        assertTrue(SecurityValidator.validateURL("http://93.184.216.34/"))  // example.com
        assertTrue(SecurityValidator.validateURL("http://151.101.1.140/"))  // stackoverflow.com
    }

    @Test
    fun testCaseInsensitivity() {
        assertFalse(SecurityValidator.validateURL("HTTP://LOCALHOST/"))
        assertFalse(SecurityValidator.validateURL("HtTpS://LocalHost:8080/"))
        assertTrue(SecurityValidator.validateURL("HTTPS://EXAMPLE.COM/"))
    }

    @Test
    fun testIPv6WithBrackets() {
        // Valid public IPv6 (example)
        assertTrue(SecurityValidator.validateURL("http://[2001:4860:4860::8888]/"))

        // Private IPv6
        assertFalse(SecurityValidator.validateURL("http://[fc00::1]/"))
        assertFalse(SecurityValidator.validateURL("http://[fe80::1]/"))
    }

    // ── IPv6 compressed loopback bypass (Fix v2.3.8) ─────────────────────────

    @Test
    fun testIPv6CompressedLoopback_shouldBeBlocked() {
        // All of these expand to 0:0:0:0:0:0:0:1 (loopback)
        assertFalse(SecurityValidator.validateURL("http://[0::1]/"),         "0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[::0:0:0:1]/"),    "::0:0:0:1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0::1]/"),       "0:0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0::1]/"),     "0:0:0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0::1]/"),   "0:0:0:0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0::1]/"),"0:0:0:0:0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0:0::1]/"),"0:0:0:0:0:0::1 is loopback")
        assertFalse(SecurityValidator.validateURL("http://[0:0:0:0:0:0:0:1]/"), "fully expanded loopback")
    }

    @Test
    fun testIPv6CompressedPublic_shouldPass() {
        // Compressed forms of public IPv6 addresses must not be blocked
        assertTrue(SecurityValidator.validateURL("http://[2001:4860::8888]/"),     "Google DNS compressed")
        assertTrue(SecurityValidator.validateURL("http://[2606:4700:4700::1111]/"), "Cloudflare DNS compressed")
        assertTrue(SecurityValidator.validateURL("http://[2001:db8::1]/"),          "Documentation prefix")
    }

    // ── Fix 6: Multi-@ UserInfo SSRF bypass ───────────────────────────────────

    @Test
    fun testMultiAtUserInfo_shouldBlockIfAnySegmentIsPrivate() {
        // Dangerous: HTTP client may connect to 127.0.0.1 despite legit.com appearing last
        assertFalse(SecurityValidator.validateURL("https://user:pass@127.0.0.1:80@legit.com/"))
        assertFalse(SecurityValidator.validateURL("https://user@10.0.0.1@api.example.com/"))
        assertFalse(SecurityValidator.validateURL("http://x@192.168.1.1:8080@external.com/path"))
        assertFalse(SecurityValidator.validateURL("http://a@169.254.169.254@b.com/"))

        // Safe: all segments after '@' are public domains / IPs
        assertTrue(SecurityValidator.validateURL("https://user:pass@api.example.com/data"))
    }

    // ── Fix 5: Case-sensitivity bypass in validateFilePath ────────────────────

    @Test
    fun testFilePathCaseSensitivityBypass_shouldBeBlocked() {
        // Uppercase percent-encoded dots — must be rejected
        assertFalse(SecurityValidator.validateFilePath("%2E%2E/etc/passwd"))
        assertFalse(SecurityValidator.validateFilePath("%2E%2E/%2E%2E/secret"))
        assertFalse(SecurityValidator.validateFilePath("%252E%252E/private"))
        assertFalse(SecurityValidator.validateFilePath("%2F%2E%2E%2Fetc%2Fpasswd"))

        // Mixed case
        assertFalse(SecurityValidator.validateFilePath("%2e%2E/traversal"))
        assertFalse(SecurityValidator.validateFilePath("%2E%2e/traversal"))

        // Valid paths still pass
        assertTrue(SecurityValidator.validateFilePath("images/photo.jpg"))
        assertTrue(SecurityValidator.validateFilePath("data/user/profile.json"))
    }

    // ── Advanced Security Fuzzing & Boundary Testing (100% Coverage Push) ──────

    @Test
    fun testAdvancedSSRFBypasses_shouldBeBlocked() {
        // Octal/Hex IP bypasses (many parsers resolve these to 127.0.0.1)
        // Note: Our looksLikeIPv4 check handles standard dots, but if an attacker uses
        // non-standard forms, they should either fail the validator or fail resolving.
        // We ensure they are caught if they parse as standard IPv4, or rejected if malformed.
        assertFalse(SecurityValidator.validateURL("http://0x7f.0.0.1/"), "Hex bypass must fail")
        assertFalse(SecurityValidator.validateURL("http://0177.0.0.1/"), "Octal bypass must fail")
        assertFalse(SecurityValidator.validateURL("http://2130706433/"), "Decimal bypass must fail")

        // IPv6 malformed and unspecified edge cases (Caught by v2.3.8 fixes)
        assertFalse(SecurityValidator.validateURL("http://[::]/"), "Unspecified address must fail")
        assertFalse(SecurityValidator.validateURL("http://[::1::1]/"), "Malformed double-compressed must fail")
        assertFalse(SecurityValidator.validateURL("http://[0000:0000:0000:0000:0000:0000:0000:0000]/"), "Expanded unspecified must fail")
    }

    @Test
    fun testAdvancedPathTraversalFuzzing_shouldBeBlocked() {
        // Null-byte injection
        assertFalse(SecurityValidator.validateFilePath("image.jpg\u0000../etc/passwd"))
        assertFalse(SecurityValidator.validateFilePath("image.jpg%00../etc/passwd"))

        // Backslash bypasses (Windows style paths escaping to Linux)
        assertFalse(SecurityValidator.validateFilePath("..\\etc\\passwd"))
        assertFalse(SecurityValidator.validateFilePath("..%5cetc%5cpasswd"))
        assertFalse(SecurityValidator.validateFilePath("%2e%2e%5c%2e%2e%5c"))

        // Sensitive OS roots
        assertFalse(SecurityValidator.validateFilePath("/etc/shadow"))
        assertFalse(SecurityValidator.validateFilePath("/proc/self/environ"))
        assertFalse(SecurityValidator.validateFilePath("/private/etc/hosts"))
    }

    // ── Fix HIGH-4: Double-slash bypass in validateFilePath ──────────────────

    @Test
    fun testDoubleSlashBypass_shouldBeBlocked() {
        // Before fix: "//etc/passwd".startsWith("/etc") == false → bypass!
        // After fix: collapsed to "/etc/passwd" → blocked
        assertFalse(SecurityValidator.validateFilePath("//etc/passwd"),
            "//etc/passwd must be blocked after double-slash collapse")
        assertFalse(SecurityValidator.validateFilePath("//proc/self/environ"),
            "//proc must be blocked")
        assertFalse(SecurityValidator.validateFilePath("///etc/shadow"),
            "Triple slash must be collapsed and blocked")
        assertFalse(SecurityValidator.validateFilePath("//dev/null"),
            "//dev must be blocked")
        assertFalse(SecurityValidator.validateFilePath("//private/etc/hosts"),
            "//private/etc must be blocked")
    }

    @Test
    fun testDoubleSlashInMiddleOfValidPath_shouldPass() {
        // Double slash in middle collapses but result is a valid path
        assertTrue(SecurityValidator.validateFilePath("uploads//photo.jpg"),
            "Double slash in middle of valid path must pass after collapse")
        assertTrue(SecurityValidator.validateFilePath("data///reports/2024.pdf"),
            "Triple slash in middle must pass after collapse")
    }
}
