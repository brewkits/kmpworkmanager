package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN

/**
 * Provider interface for querying current network state on iOS.
 * Abstracted to decouple Native C-interop logic from Business Logic and to enable
 * mock testing.
 */
interface IosNetworkStateProvider {
    /**
     * Returns true if the device is currently using a cellular/mobile data connection.
     * Must return false on error or if indeterminate to avoid silently blocking legitimate work.
     */
    fun isNetworkCellular(): Boolean
}

/**
 * Default implementation using SCNetworkReachability.
 * 
 * Note: Apple deprecated this in iOS 16 in favour of NWPathMonitor.
 * kSCNetworkReachabilityFlagsIsWWAN was zeroed out on iOS 16+ real hardware.
 * This is kept as a conservative fallback (allows execution rather than blocking)
 * until a full NWPathMonitor integration with lifecycle support is built.
 */
class DefaultIosNetworkStateProvider : IosNetworkStateProvider {

    @OptIn(ExperimentalForeignApi::class)
    @Suppress("DEPRECATION")
    override fun isNetworkCellular(): Boolean {
        val reachability = SCNetworkReachabilityCreateWithName(null, "1.1.1.1") ?: return false
        return try {
            memScoped {
                val flags = alloc<SCNetworkReachabilityFlagsVar>()
                SCNetworkReachabilityGetFlags(reachability, flags.ptr) &&
                    (flags.value and kSCNetworkReachabilityFlagsIsWWAN) != 0u
            }
        } catch (e: Exception) {
            Logger.w(LogTags.CHAIN, "isNetworkCellular() check failed — assuming non-cellular: ${e.message}")
            false
        } finally {
            CFRelease(reachability)
        }
    }
}
