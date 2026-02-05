package dev.brewkits.kmpworkmanager.utils

import kotlinx.cinterop.*
import platform.zlib.crc32

/**
 * iOS platform-specific CRC32 implementation using zlib
 *
 * **Performance:**
 * - Uses native C implementation from zlib library (included in iOS SDK)
 * - 5-10x faster than pure Kotlin implementation
 * - Zero-copy operation with memory pinning
 *
 * **Implementation Notes:**
 * - Uses kotlinx.cinterop for safe C interop
 * - Memory is pinned during calculation to prevent GC moves
 * - IEEE 802.3 polynomial (same as pure Kotlin version)
 * - Returns UInt for consistency with common API
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object CRC32Platform {
    actual fun calculate(data: ByteArray): UInt {
        if (data.isEmpty()) {
            return 0u  // Edge case: empty data
        }

        return data.usePinned { pinned ->
            // Call zlib's crc32 function
            // Signature: uLong crc32(uLong crc, const Bytef *buf, uInt len)
            val result = crc32(
                0u.convert(),  // Initial CRC value (0)
                pinned.addressOf(0).reinterpret(),  // Pointer to data
                data.size.toUInt()  // Data length
            )
            result.toUInt()
        }
    }
}
