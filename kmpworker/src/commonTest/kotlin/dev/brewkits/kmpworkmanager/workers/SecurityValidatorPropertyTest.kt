package dev.brewkits.kmpworkmanager.workers

import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Property-based tests for SecurityValidator using Kotest
 *
 * These tests verify security validation logic across a wide range of generated inputs,
 * ensuring robustness against edge cases and malicious inputs.
 *
 * Added in v2.3.2
 */
class SecurityValidatorPropertyTest {

    /**
     * Property: All localhost variations should be blocked
     */
    @Test
    fun property_localhostVariationsShouldBeBlocked() = runPropertyTest {
        // Generate localhost variations
        val localhostPatterns = listOf("localhost", "LOCALHOST", "LocalHost", "127.0.0.1", "0.0.0.0")
        val schemes = listOf("http", "https", "HTTP", "HTTPS")
        val paths = Arb.list(Arb.string(1..10, Codepoint.alphanumeric()), 0..3)

        checkAll(
            iterations = 100,
            Arb.choice(localhostPatterns.map { Arb.constant(it) }),
            Arb.choice(schemes.map { Arb.constant(it) }),
            paths
        ) { host, scheme, pathSegments ->
            val path = if (pathSegments.isEmpty()) "" else "/" + pathSegments.joinToString("/")
            val url = "$scheme://$host$path"
            assertFalse(
                SecurityValidator.validateURL(url),
                "Localhost URL should be blocked: $url"
            )
        }
    }

    /**
     * Property: All private IPv4 addresses should be blocked
     */
    @Test
    fun property_privateIPv4ShouldBeBlocked() = runPropertyTest {
        // Generate private IP ranges
        checkAll(iterations = 200) { seed: Int ->
            val random = kotlin.random.Random(seed)

            // 10.0.0.0/8
            val class10IP = "http://10.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(256)}/api"
            assertFalse(SecurityValidator.validateURL(class10IP), "10.0.0.0/8 should be blocked: $class10IP")

            // 172.16.0.0/12
            val class172IP = "http://172.${random.nextInt(16, 32)}.${random.nextInt(256)}.${random.nextInt(256)}/api"
            assertFalse(SecurityValidator.validateURL(class172IP), "172.16.0.0/12 should be blocked: $class172IP")

            // 192.168.0.0/16
            val class192IP = "http://192.168.${random.nextInt(256)}.${random.nextInt(256)}/api"
            assertFalse(SecurityValidator.validateURL(class192IP), "192.168.0.0/16 should be blocked: $class192IP")

            // 169.254.0.0/16 (link-local)
            val linkLocalIP = "http://169.254.${random.nextInt(256)}.${random.nextInt(256)}/api"
            assertFalse(SecurityValidator.validateURL(linkLocalIP), "Link-local should be blocked: $linkLocalIP")
        }
    }

    /**
     * Property: Valid public URLs should always pass
     */
    @Test
    fun property_validPublicURLsShouldPass() = runPropertyTest {
        val validDomains = listOf(
            "api.example.com",
            "google.com",
            "github.com",
            "stackoverflow.com",
            "mozilla.org"
        )

        val schemes = listOf("http", "https")
        val paths = Arb.list(Arb.string(1..20, Codepoint.alphanumeric()), 0..5)

        checkAll(
            iterations = 100,
            Arb.choice(validDomains.map { Arb.constant(it) }),
            Arb.choice(schemes.map { Arb.constant(it) }),
            paths
        ) { domain, scheme, pathSegments ->
            val path = if (pathSegments.isEmpty()) "" else "/" + pathSegments.joinToString("/")
            val url = "$scheme://$domain$path"
            assertTrue(
                SecurityValidator.validateURL(url),
                "Valid public URL should pass: $url"
            )
        }
    }

    /**
     * Property: URLs with invalid schemes should be rejected
     */
    @Test
    fun property_invalidSchemesShouldBeRejected() = runPropertyTest {
        val invalidSchemes = listOf("ftp", "file", "javascript", "data", "ssh", "telnet")
        val domains = Arb.string(5..20, Codepoint.alphanumeric())

        checkAll(
            iterations = 50,
            Arb.choice(invalidSchemes.map { Arb.constant(it) }),
            domains
        ) { scheme, domain ->
            val url = "$scheme://$domain.com/path"
            assertFalse(
                SecurityValidator.validateURL(url),
                "Invalid scheme should be rejected: $url"
            )
        }
    }

    /**
     * Property: IPv6 localhost should be blocked
     */
    @Test
    fun property_ipv6LocalhostShouldBeBlocked() = runPropertyTest {
        val ipv6Localhost = listOf("::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]")

        checkAll(
            iterations = 40,
            Arb.choice(ipv6Localhost.map { Arb.constant(it) })
        ) { host ->
            val url = "http://$host/api"
            assertFalse(
                SecurityValidator.validateURL(url),
                "IPv6 localhost should be blocked: $url"
            )
        }
    }

    /**
     * Property: Private IPv6 ranges should be blocked
     */
    @Test
    fun property_privateIPv6ShouldBeBlocked() = runPropertyTest {
        // fc00::/7 (ULA - Unique Local Addresses)
        val ulaPatterns = listOf("fc00::", "fd00::", "fcff::", "fdff::")

        // fe80::/10 (Link-local)
        val linkLocalPatterns = listOf("fe80::", "fe8f::", "febf::")

        checkAll(
            iterations = 70,
            Arb.choice((ulaPatterns + linkLocalPatterns).map { Arb.constant(it) })
        ) { ipv6 ->
            val url = "http://[$ipv6]/api"
            assertFalse(
                SecurityValidator.validateURL(url),
                "Private IPv6 should be blocked: $url"
            )
        }
    }

    /**
     * Property: File paths with ".." should be rejected
     */
    @Test
    fun property_pathTraversalShouldBeRejected() = runPropertyTest {
        checkAll(iterations = 100) { seed: Int ->
            val random = kotlin.random.Random(seed)
            val segments = List(random.nextInt(1, 5)) {
                if (random.nextBoolean()) ".." else "validdir"
            }
            val path = segments.joinToString("/")

            if (path.contains("..")) {
                assertFalse(
                    SecurityValidator.validateFilePath(path),
                    "Path with .. should be rejected: $path"
                )
            }
        }
    }

    /**
     * Property: Sanitized URLs should not contain query parameters
     */
    @Test
    fun property_sanitizedURLsHideQueryParams() = runPropertyTest {
        val domains = Arb.string(5..20, Codepoint.alphanumeric())
        val queryKeys = Arb.string(3..10, Codepoint.alphanumeric())
        val queryValues = Arb.string(5..30, Codepoint.alphanumeric())

        checkAll(
            iterations = 100,
            domains,
            queryKeys,
            queryValues
        ) { domain, key, value ->
            val url = "https://$domain.com/api?$key=$value"
            val sanitized = SecurityValidator.sanitizedURL(url)

            assertTrue(
                sanitized.contains("[REDACTED]") || !sanitized.contains(value),
                "Sanitized URL should hide sensitive data: original=$url, sanitized=$sanitized"
            )
        }
    }

    /**
     * Property: Truncated strings should respect max length
     */
    @Test
    fun property_truncatedStringsRespectMaxLength() = runPropertyTest {
        val strings = Arb.string(0..500, Codepoint.ascii())
        val maxLengths = Arb.int(10..200)

        checkAll(
            iterations = 100,
            strings,
            maxLengths
        ) { str, maxLength ->
            val truncated = SecurityValidator.truncateForLogging(str, maxLength)

            assertTrue(
                truncated.length <= maxLength + 10, // +10 for "..." and possible suffix
                "Truncated string should not exceed max length: len=${truncated.length}, max=$maxLength"
            )
        }
    }

    /**
     * Property: Mixed case schemes should be handled correctly
     */
    @Test
    fun property_mixedCaseSchemesShouldBeHandled() = runPropertyTest {
        val schemeVariations = listOf("http", "HTTP", "Http", "hTTp", "https", "HTTPS", "Https", "hTTPs")
        val domains = listOf("example.com", "api.test.org", "github.com")

        checkAll(
            iterations = 80,
            Arb.choice(schemeVariations.map { Arb.constant(it) }),
            Arb.choice(domains.map { Arb.constant(it) })
        ) { scheme, domain ->
            val url = "$scheme://$domain/api"
            val isHttpScheme = scheme.lowercase() == "http" || scheme.lowercase() == "https"

            if (isHttpScheme) {
                assertTrue(
                    SecurityValidator.validateURL(url),
                    "Valid HTTP scheme (case-insensitive) should pass: $url"
                )
            }
        }
    }

    /**
     * Helper function to run property tests with better error messages
     */
    private fun runPropertyTest(block: suspend () -> Unit) {
        try {
            kotlinx.coroutines.runBlocking {
                block()
            }
        } catch (e: AssertionError) {
            throw AssertionError("Property test failed: ${e.message}", e)
        }
    }
}
