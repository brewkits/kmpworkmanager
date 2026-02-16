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
}
