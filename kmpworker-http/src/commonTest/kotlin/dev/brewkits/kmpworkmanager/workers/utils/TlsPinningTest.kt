package dev.brewkits.kmpworkmanager.workers.utils

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract tests for the pinning configuration layer.
 *
 * These cover what is testable without a live TLS handshake: validation of the pin format,
 * host lookup, and — the one that matters most for a library — that a host which never calls
 * `configurePinning` is completely unaffected.
 *
 * What they deliberately do **not** claim to cover is the platform verification itself: whether
 * OkHttp's `CertificatePinner` and the Darwin challenge handler accept the right chain and
 * reject the wrong one. That needs a real handshake against a known server. See
 * `TlsPinningNetworkTest` for that, which is opt-in because it requires the network.
 */
class TlsPinningTest {

    @AfterTest
    fun reset() {
        // The config is process-wide; leaving pins behind would change how every later test
        // in this binary builds its client.
        TlsPinningConfig.set(emptyList())
    }

    // ---- The property that keeps this opt-in ----------------------------------------

    /**
     * A host that never configures pinning must see no pins at all. If this ever fails, some
     * default has crept in and every existing app's HTTPS traffic is being validated against
     * something it did not ask for.
     */
    @Test
    fun withNoConfigurationNothingIsPinned() {
        assertTrue(TlsPinningConfig.pins.isEmpty(), "pinning must be off unless configured")
        assertTrue(TlsPinningConfig.pinsFor("api.example.com").isEmpty())
    }

    /** Configuring one host must not start pinning every other host. */
    @Test
    fun configuringOneHostLeavesOtherHostsUnpinned() {
        TlsPinningConfig.set(
            listOf(CertificatePin("api.example.com", listOf("sha256/AAAA")))
        )

        assertEquals(1, TlsPinningConfig.pinsFor("api.example.com").size)
        assertTrue(
            TlsPinningConfig.pinsFor("cdn.example.com").isEmpty(),
            "an unrelated host must stay on default validation"
        )
    }

    /** Passing an empty list is the documented way to turn pinning back off. */
    @Test
    fun anEmptyListRemovesPinning() {
        TlsPinningConfig.set(listOf(CertificatePin("api.example.com", listOf("sha256/AAAA"))))
        TlsPinningConfig.set(emptyList())

        assertTrue(TlsPinningConfig.pinsFor("api.example.com").isEmpty())
    }

    /** Hostnames are compared case-insensitively, as DNS is. */
    @Test
    fun hostLookupIgnoresCase() {
        TlsPinningConfig.set(listOf(CertificatePin("API.Example.COM", listOf("sha256/AAAA"))))

        assertEquals(1, TlsPinningConfig.pinsFor("api.example.com").size)
    }

    /** Several pins for one host are all offered; any one matching is enough. */
    @Test
    fun multiplePinsForOneHostAreAllRetained() {
        TlsPinningConfig.set(
            listOf(CertificatePin("api.example.com", listOf("sha256/AAAA", "sha256/BBBB")))
        )

        assertEquals(
            listOf("sha256/AAAA", "sha256/BBBB"),
            TlsPinningConfig.pinsFor("api.example.com")
        )
    }

    // ---- Rejecting configurations that would fail silently or fail everything --------

    /**
     * An entry with no pins would reject every connection to that host. Failing at
     * construction turns a total outage into a startup crash the developer sees immediately.
     */
    @Test
    fun anEntryWithNoPinsIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            CertificatePin("api.example.com", emptyList())
        }
        assertContains(error.message ?: "", "reject every connection")
    }

    @Test
    fun aBlankHostnameIsRejected() {
        assertFailsWith<IllegalArgumentException> { CertificatePin("   ", listOf("sha256/AAAA")) }
    }

    /**
     * The `sha256/` prefix is not decoration — it is how the value is distinguished from the
     * two things people reach for by mistake: a hex digest, or the hash of the whole
     * certificate rather than of its SubjectPublicKeyInfo. Either would simply never match,
     * and the symptom would be an app that cannot reach its own server.
     */
    @Test
    fun aPinWithoutTheSha256PrefixIsRejected() {
        listOf(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",     // missing prefix
            "sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",        // wrong algorithm
            "deadbeef",                                          // hex digest
        ).forEach { bad ->
            val error = assertFailsWith<IllegalArgumentException>("must reject '$bad'") {
                CertificatePin("api.example.com", listOf(bad))
            }
            assertContains(error.message ?: "", "sha256/")
        }
    }

    @Test
    fun aPinWithNoDigestAfterThePrefixIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePin("api.example.com", listOf("sha256/"))
        }
    }

    /** The stored list must not alias the caller's, or a later mutation would change pinning. */
    @Test
    fun theConfiguredListIsCopiedNotAliased() {
        val mutable = mutableListOf(CertificatePin("api.example.com", listOf("sha256/AAAA")))
        TlsPinningConfig.set(mutable)
        mutable.clear()

        assertEquals(
            1,
            TlsPinningConfig.pinsFor("api.example.com").size,
            "clearing the caller's list must not silently disable pinning"
        )
    }
}
