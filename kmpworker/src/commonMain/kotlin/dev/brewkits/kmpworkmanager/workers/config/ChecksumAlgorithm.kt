package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Hash algorithm used to verify a downloaded file's integrity end-to-end.
 *
 * SHA-256 is the recommended default — fast on every device released in the last
 * decade and cryptographically strong. MD5 / SHA-1 are kept for compatibility with
 * legacy backends that publish only those checksums; do not pick them for new
 * code. SHA-512 is offered for compliance environments that mandate it.
 */
@Serializable
enum class ChecksumAlgorithm {
    MD5,
    SHA1,
    SHA256,
    SHA512;

    companion object {
        /**
         * Accept Flutter-style strings (`"SHA-256"`, `"sha-1"`, …) so a config payload
         * shared between Flutter and KMP scheduling layers parses identically.
         * Unknown values throw — this is config validation, not free-form input.
         */
        fun parse(raw: String): ChecksumAlgorithm = when (raw.uppercase().replace("-", "")) {
            "MD5" -> MD5
            "SHA1" -> SHA1
            "SHA256" -> SHA256
            "SHA512" -> SHA512
            else -> throw IllegalArgumentException(
                "Unsupported checksum algorithm: '$raw'. " +
                    "Supported: MD5, SHA-1, SHA-256, SHA-512."
            )
        }
    }
}
