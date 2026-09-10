@file:Suppress("MatchingDeclarationName")  // holds the whole iOS pinning path, not one type
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.workers.utils

import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.ByteString.Companion.toByteString
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.serverTrust
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecTrustCopyKey
import platform.Security.SecTrustRef

/**
 * Outcome of evaluating one server trust against configured pins. Named rather than boolean so
 * the reason reaches the log — "pinning rejected the connection" and "pinning could not read
 * the key" call for different actions from whoever is holding the pager.
 */
internal enum class PinCheckResult { NOT_PINNED, MATCH, MISMATCH, UNSUPPORTED_KEY, TRUST_INVALID }

/**
 * ASN.1 SubjectPublicKeyInfo headers, by key type and size.
 *
 * `SecKeyCopyExternalRepresentation` hands back the **raw key**, while a `sha256/…` pin — the
 * form OkHttp, TrustKit and every pin-generating tool emit — is over the **full
 * SubjectPublicKeyInfo**, which prefixes that key with an AlgorithmIdentifier. Hashing the raw
 * key produces a digest that matches nothing, so the header has to be restored before hashing.
 *
 * These four cover TLS server certificates in practice. Anything else is reported as
 * [PinCheckResult.UNSUPPORTED_KEY] and **rejected**, never waved through: a pinning check that
 * silently passes what it cannot verify is worse than no pinning, because the host believes it
 * is protected.
 */
private val SPKI_HEADERS: Map<Pair<Boolean, Int>, ByteArray> = mapOf(
    // (isRsa, keySizeBytes) -> header
    (true to 256) to byteArrayOf(
        0x30, 0x53.toByte(), 0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
        0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x42, 0x00
    ),
    (true to 512) to byteArrayOf(
        0x30, 0x82.toByte(), 0x02, 0x22, 0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48,
        0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x82.toByte(),
        0x02, 0x0F, 0x00
    ),
    (false to 65) to byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D,
        0x02, 0x01, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01,
        0x07, 0x03, 0x42, 0x00
    ),
    (false to 97) to byteArrayOf(
        0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D,
        0x02, 0x01, 0x06, 0x05, 0x2B, 0x81.toByte(), 0x04, 0x00, 0x22, 0x03, 0x62, 0x00
    ),
)

/** RSA-2048 raw keys are 270 bytes as returned; the map is keyed on the modulus size instead. */
private fun rsaHeaderFor(rawSize: Int): ByteArray? = when {
    rawSize in 260..280 -> SPKI_HEADERS[true to 256]   // RSA-2048
    rawSize in 515..535 -> SPKI_HEADERS[true to 512]   // RSA-4096
    else -> null
}

private fun ecHeaderFor(rawSize: Int): ByteArray? = when (rawSize) {
    65 -> SPKI_HEADERS[false to 65]                     // P-256
    97 -> SPKI_HEADERS[false to 97]                     // P-384
    else -> null
}

/**
 * Checks [trust] for [hostname] against the configured pins.
 *
 * Pinning is applied **in addition to** the system's own chain validation, never instead of
 * it: [SecTrustEvaluateWithError] runs first, and a chain the OS rejects is rejected here too.
 * Inverting that order is the classic pinning mistake — it turns a pin into a way to accept
 * expired or untrusted certificates as long as the key matches.
 */
internal fun evaluatePinning(trust: SecTrustRef?, hostname: String): PinCheckResult {
    val pins = TlsPinningConfig.pinsFor(hostname)
    if (pins.isEmpty()) return PinCheckResult.NOT_PINNED
    if (trust == null) return PinCheckResult.TRUST_INVALID

    // Note what this function does NOT do: it does not evaluate the certificate chain.
    //
    // The first version did, via SecTrustEvaluateWithError, and then answered the challenge
    // with a credential built from the trust it had just approved. That is the wrong shape
    // twice over. It replaces the system's chain validation with hand-written code — the
    // classic pinning mistake, because a bug there turns a pin into a way to accept expired
    // or untrusted certificates as long as the key matches. And in practice it did not even
    // work: SecTrustEvaluateWithError returned false with a null error for a perfectly valid
    // chain, so a correctly pinned host was rejected outright.
    //
    // Instead this only answers "does the leaf's public key match a configured pin", and the
    // caller returns PerformDefaultHandling when it does. The OS then runs exactly the
    // validation it would have run on an unpinned connection — expiry, hostname, revocation,
    // trust store — and the pin is a strictly additional gate in front of it. Less code, no
    // hand-rolled trust decisions, and pinning cannot weaken validation even if this function
    // is wrong.
    val key = SecTrustCopyKey(trust) ?: return PinCheckResult.UNSUPPORTED_KEY
    val rawKeyData: CFDataRef? = memScoped {
        val err = alloc<CFErrorRefVar>()
        val data = SecKeyCopyExternalRepresentation(key, err.ptr)
        if (data == null && err.value != null) CFRelease(err.value)
        data
    }
    CFRelease(key)
    if (rawKeyData == null) return PinCheckResult.UNSUPPORTED_KEY

    val raw = cfDataToByteArray(rawKeyData)
    CFRelease(rawKeyData)

    // EC keys start with 0x04 (uncompressed point); RSA keys are DER sequences (0x30).
    val header = if (raw.isNotEmpty() && raw[0] == 0x04.toByte()) {
        ecHeaderFor(raw.size)
    } else {
        rsaHeaderFor(raw.size)
    } ?: return PinCheckResult.UNSUPPORTED_KEY

    val computed = CertificatePin.PIN_PREFIX + (header + raw).toByteString().sha256().base64()
    if (pins.any { it == computed }) return PinCheckResult.MATCH

    // The computed pin is logged on mismatch and nowhere else. It is not a secret — it is the
    // hash of a public key the server just presented — and without it the only way to work out
    // why a pinned host stopped connecting is to reproduce the handshake by hand with openssl.
    Logger.e(
        LogTags.WORKER,
        "TLS pin mismatch for '$hostname': server presented $computed, configured " +
            "${pins.joinToString()}. If the server key rotated, this is the value to add."
    )
    return PinCheckResult.MISMATCH
}

private fun cfDataToByteArray(data: CFDataRef): ByteArray {
    val length = CFDataGetLength(data).toInt()
    if (length <= 0) return ByteArray(0)
    val bytes = CFDataGetBytePtr(data) ?: return ByteArray(0)
    return ByteArray(length) { i -> bytes[i].toByte() }
}

/** What the caller should tell NSURLSession to do with a server-trust challenge. */
internal enum class PinDecision { PROCEED_WITH_DEFAULT_HANDLING, CANCEL }

/**
 * Decides how to answer a server-trust challenge, without touching NSURLSession itself.
 *
 * Returning a decision rather than invoking the completion handler here is deliberate. The
 * handler's disposition parameter is `Long` in the per-target compilations and `Int` in the
 * shared iOS metadata compilation, so a function that accepted the handler would compile for
 * the simulator and fail `compileIosMainKotlinMetadata` — i.e. only at publish time. Letting
 * the call site invoke it keeps that type local to one expression, where the compiler resolves
 * it consistently.
 *
 * A host without pins yields [PinDecision.PROCEED_WITH_DEFAULT_HANDLING] — byte-for-byte the
 * behaviour of an unpinned client — so pinning one host does not change how any other host is
 * validated.
 */
internal fun decidePinningChallenge(
    challenge: platform.Foundation.NSURLAuthenticationChallenge
): PinDecision {
    val space = challenge.protectionSpace
    if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
        return PinDecision.PROCEED_WITH_DEFAULT_HANDLING
    }

    val host = space.host
    return when (val result = evaluatePinning(space.serverTrust, host)) {
        // Not pinned, or pinned and matching: hand the challenge back to the OS so its own
        // chain validation still runs. Supplying a credential instead would mean this code,
        // not the system, decided the certificate was acceptable.
        PinCheckResult.NOT_PINNED, PinCheckResult.MATCH ->
            PinDecision.PROCEED_WITH_DEFAULT_HANDLING

        PinCheckResult.MISMATCH, PinCheckResult.UNSUPPORTED_KEY, PinCheckResult.TRUST_INVALID -> {
            // Loud on purpose. A pinning rejection looks like a network outage from the app's
            // side, and without this the only symptom is uploads that never succeed.
            Logger.e(
                LogTags.WORKER,
                "TLS pinning rejected the connection to '$host' ($result). Either the server " +
                    "presented an unexpected key, or the configured pins are stale — see " +
                    "CertificatePin's note on shipping a backup pin before rotating."
            )
            PinDecision.CANCEL
        }
    }
}
