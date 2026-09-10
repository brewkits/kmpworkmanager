package dev.brewkits.kmpworkmanager.workers.utils

import kotlin.concurrent.Volatile

/**
 * SHA-256 pins for one hostname's TLS certificate chain.
 *
 * A pin is the base64 SHA-256 of a certificate's **SubjectPublicKeyInfo**, in the same
 * `sha256/BASE64` form OkHttp and most tooling use. Pinning the public key rather than the
 * whole certificate is what lets a server renew its certificate — same key, new expiry —
 * without shipping an app update.
 *
 * ```kotlin
 * HttpClientProvider.configurePinning(
 *     listOf(
 *         CertificatePin(
 *             hostname = "api.example.com",
 *             sha256Pins = listOf(
 *                 "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",  // current leaf
 *                 "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",  // backup
 *             ),
 *         ),
 *     ),
 * )
 * ```
 *
 * ### Always ship a backup pin
 *
 * This is the failure that makes teams abandon pinning, and it is not recoverable remotely:
 * pin only the key you are using today, rotate that key (or have your CA rotate it for you),
 * and every installed copy of the app loses the ability to reach the server. There is no
 * server-side fix, because the app will not talk to the server. Pin at least one key you are
 * not using yet — typically the intermediate CA, or a backup key held offline — and treat the
 * pin list as something that must be updated *before* a rotation, not after.
 *
 * Reaching the pinned host is also the only way to find out the pins are wrong, so verify
 * against a build that actually performs a request before shipping.
 *
 * @property hostname Host to pin, e.g. `api.example.com`. Matched exactly on iOS; on Android
 *   OkHttp additionally supports a `*.` wildcard prefix.
 * @property sha256Pins One or more `sha256/BASE64` pins. Any single match accepts the chain.
 */
public data class CertificatePin(
    val hostname: String,
    val sha256Pins: List<String>
) {
    init {
        require(hostname.isNotBlank()) { "hostname cannot be blank" }
        require(sha256Pins.isNotEmpty()) {
            "sha256Pins cannot be empty — a pin entry with no pins would reject every " +
                "connection to $hostname"
        }
        sha256Pins.forEach { pin ->
            require(pin.startsWith(PIN_PREFIX)) {
                "pin must start with '$PIN_PREFIX', got '$pin'. The value is the base64 " +
                    "SHA-256 of the certificate's SubjectPublicKeyInfo, not of the whole " +
                    "certificate and not hex."
            }
            require(pin.length > PIN_PREFIX.length) { "pin '$pin' has no digest after the prefix" }
        }
    }

    public companion object {
        internal const val PIN_PREFIX: String = "sha256/"
    }
}

/**
 * Process-wide pinning configuration read by the platform HTTP client when it is built.
 *
 * Deliberately not a constructor parameter: [HttpClientProvider] is a singleton whose client
 * is created on first access, and threading configuration through `createPlatformHttpClient()`
 * would change an `internal expect` signature that both platform actuals implement. The
 * trade-off is ordering, which [HttpClientProvider.configurePinning] handles by discarding any
 * client that was already built.
 */
internal object TlsPinningConfig {

    @Volatile
    private var current: List<CertificatePin> = emptyList()

    val pins: List<CertificatePin> get() = current

    fun set(pins: List<CertificatePin>) {
        current = pins.toList()
    }

    /** Pins for [hostname], or empty when this host is not pinned. */
    fun pinsFor(hostname: String): List<String> =
        current.filter { it.hostname.equals(hostname, ignoreCase = true) }
            .flatMap { it.sha256Pins }
}
