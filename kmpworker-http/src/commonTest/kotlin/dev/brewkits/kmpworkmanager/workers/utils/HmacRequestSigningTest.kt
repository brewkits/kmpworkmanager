package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.workers.config.HmacSigningConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [computeHmacSignature] — pure-function coverage, no HTTP client involved.
 *
 * The known-vector test pins the exact canonical-string format (`METHOD\nURL\nBODY\nTIMESTAMP`)
 * against an independently computed HMAC-SHA256 digest, so a future refactor that
 * accidentally reorders or drops a line breaks loudly instead of just changing the output
 * silently (both sides of a client/server pair must agree on the exact canonical format).
 */
class HmacRequestSigningTest {

    private val secret = "0123456789abcdef" // exactly 16 chars — the minimum allowed

    // HMAC-SHA256("0123456789abcdef", "POST\nhttps://api.example.com/upload\nhello\n1700000000000")
    // computed independently via Python's hmac/hashlib for cross-check.
    private val knownVectorSignature = "72cadd50011f98595398c334c9543dc53380f430ec5355a7823a66c2ef46d9fd".let {
        // Guard against a typo in the constant above breaking every test with a confusing
        // "expected 64 chars" failure far from the actual assertion.
        require(it.length == 64) { "test constant must be a 64-char SHA-256 hex digest" }
        it
    }

    @Test
    fun knownVector_matchesIndependentlyComputedDigest() {
        val config = HmacSigningConfig(secretKey = secret)

        val signature = computeHmacSignature(
            method = "post", // lowercase on purpose — canonical form uppercases it
            url = "https://api.example.com/upload",
            body = "hello",
            timestamp = "1700000000000",
            config = config
        )

        assertEquals(knownVectorSignature, signature)
    }

    @Test
    fun signaturePrefix_isPrependedToTheHexDigest() {
        val config = HmacSigningConfig(secretKey = secret, signaturePrefix = "sha256=")

        val signature = computeHmacSignature("POST", "https://x", "body", "123", config)

        assertTrue(signature.startsWith("sha256="))
        assertEquals("sha256=" + computeHmacSignature("POST", "https://x", "body", "123", config.copy(signaturePrefix = null)), signature)
    }

    @Test
    fun signBodyFalse_ignoresBodyDifferences() {
        val config = HmacSigningConfig(secretKey = secret, signBody = false)

        val withBodyA = computeHmacSignature("POST", "https://x", "body-a", "123", config)
        val withBodyB = computeHmacSignature("POST", "https://x", "body-b", "123", config)

        assertEquals(withBodyA, withBodyB, "signBody=false must make the signature independent of the body content")
    }

    @Test
    fun signBodyTrue_differsAcrossBodies() {
        val config = HmacSigningConfig(secretKey = secret, signBody = true)

        val withBodyA = computeHmacSignature("POST", "https://x", "body-a", "123", config)
        val withBodyB = computeHmacSignature("POST", "https://x", "body-b", "123", config)

        assertNotEquals(withBodyA, withBodyB)
    }

    @Test
    fun differentSecretKeys_produceDifferentSignatures() {
        val a = computeHmacSignature("POST", "https://x", "body", "123", HmacSigningConfig(secretKey = "aaaaaaaaaaaaaaaa"))
        val b = computeHmacSignature("POST", "https://x", "body", "123", HmacSigningConfig(secretKey = "bbbbbbbbbbbbbbbb"))

        assertNotEquals(a, b)
    }

    @Test
    fun secretKeyUnder16Chars_isRejectedAtConstruction() {
        val error = kotlin.runCatching { HmacSigningConfig(secretKey = "short") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected construction to fail, got: $error")
    }
}
