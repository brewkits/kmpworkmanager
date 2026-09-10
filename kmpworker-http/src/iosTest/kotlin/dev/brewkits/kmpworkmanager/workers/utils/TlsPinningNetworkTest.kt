@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.workers.utils

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the iOS pinning path against a **real TLS handshake**.
 *
 * ### Why this cannot be a normal unit test
 *
 * `TlsPinningTest` covers configuration. It cannot cover the part most likely to be wrong: on
 * iOS the pin is computed by hand, because `SecKeyCopyExternalRepresentation` returns the raw
 * public key while a `sha256/…` pin is over the full SubjectPublicKeyInfo — so the ASN.1
 * AlgorithmIdentifier header has to be reattached before hashing, and it differs per key type
 * and size. Get that wrong and the digest matches nothing: pinning would reject every
 * connection, which is an app that cannot reach its own server. Nothing short of a real
 * handshake distinguishes a correct implementation from that.
 *
 * Android is not covered here because it delegates to OkHttp's `CertificatePinner`, which does
 * its own SPKI hashing and is far better tested than anything written here would be.
 *
 * ### Opt-in
 *
 * Needs the network, and pins a host this project does not control, so it is gated like the
 * stress tests and announces itself when it skips:
 *
 * ```
 * KMP_RUN_NETWORK_TESTS=1 ./gradlew :kmpworker-http:iosSimulatorArm64Test
 * ```
 *
 * If it starts failing with a mismatch and the implementation has not changed, the pin below
 * has rotated — recompute it, which is itself the lesson `CertificatePin` documents:
 *
 * ```
 * openssl s_client -servername www.example.com -connect www.example.com:443 </dev/null \
 *   | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary | openssl enc -base64
 * ```
 */
class TlsPinningNetworkTest {

    private companion object {
        const val HOST = "www.example.com"

        /** Leaf SPKI pin, EC P-256, captured 2026-09-10. */
        const val REAL_PIN = "sha256/CFZ1L1MZmmc9zJVcE3/h9bEFoYBSissyC7Pt3xUQOps="

        /** Well-formed and certainly wrong. */
        const val WRONG_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /**
         * True when this machine can complete a normal, unpinned TLS handshake to [HOST].
         *
         * Without this guard every assertion below reports a pinning failure on any machine
         * whose trust store or network cannot validate the host — which is how the tests
         * behaved when first written here: an unpinned request to www.apple.com,
         * cloudflare.com and example.com all failed with NSURLErrorServerCertificateUntrusted,
         * so "pinning is broken" and "this simulator cannot do TLS" were indistinguishable.
         * A test that cannot tell those apart is the false-red that gets suites muted.
         */
        suspend fun baselineTlsWorks(): Boolean {
            HttpClientProvider.configurePinning(emptyList())
            return try {
                HttpClientProvider.instance.get("https://$HOST/").status.value in 200..399
            } catch (e: Exception) {
                println(
                    "⏭️  NETWORK TEST SKIPPED: this environment cannot complete an UNPINNED " +
                        "TLS handshake to $HOST (${e.message?.take(80)}). Nothing here can " +
                        "distinguish a pinning bug from that, so the assertions are skipped."
                )
                false
            }
        }

        fun skip(name: String): Boolean {
            val raw = getenv("KMP_RUN_NETWORK_TESTS")?.toKString()?.lowercase()
            if (raw == "1" || raw == "true" || raw == "yes") return false
            println("⏭️  NETWORK TEST SKIPPED: $name — set KMP_RUN_NETWORK_TESTS=1 to run it")
            return true
        }
    }

    @AfterTest
    fun reset() {
        HttpClientProvider.configurePinning(emptyList())
    }

    /**
     * The real check: the SPKI digest this implementation computes for a live server must
     * equal the one `openssl` computes for the same server. A wrong ASN.1 header, a hash over
     * the raw key instead of the SPKI, or the wrong encoding all fail here.
     */
    @Test
    fun aCorrectPinLetsTheRequestThrough() = runTest {
        if (skip("correct pin allows the connection")) return@runTest
        if (!baselineTlsWorks()) return@runTest

        HttpClientProvider.configurePinning(
            listOf(CertificatePin(HOST, listOf(REAL_PIN)))
        )

        val response: HttpResponse = HttpClientProvider.instance.get("https://$HOST/")
        assertTrue(
            response.status.value in 200..399,
            "a correctly pinned host must still be reachable, got ${response.status}"
        )
    }

    /**
     * The other half. A pinner that accepts everything passes the test above and protects
     * nothing, so this asserts the rejection — the property that makes pinning worth having.
     */
    @Test
    fun aWrongPinBlocksTheRequest() = runTest {
        if (skip("wrong pin blocks the connection")) return@runTest
        if (!baselineTlsWorks()) return@runTest

        HttpClientProvider.configurePinning(
            listOf(CertificatePin(HOST, listOf(WRONG_PIN)))
        )

        var failed = false
        try {
            HttpClientProvider.instance.get("https://$HOST/")
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(
            failed,
            "a wrong pin must break the connection. If this passes, pinning is accepting " +
                "whatever the server presents and is protection in name only."
        )
    }

    /** Unpinned hosts keep working while another host is pinned. */
    @Test
    fun anUnpinnedHostIsUnaffectedWhilePinningIsActive() = runTest {
        if (skip("unpinned host unaffected")) return@runTest
        if (!baselineTlsWorks()) return@runTest

        HttpClientProvider.configurePinning(
            listOf(CertificatePin("some-other-host.invalid", listOf(WRONG_PIN)))
        )

        val response: HttpResponse = HttpClientProvider.instance.get("https://$HOST/")
        assertTrue(
            response.status.value in 200..399,
            "pinning one host must not disturb another, got ${response.status}"
        )
    }
}
