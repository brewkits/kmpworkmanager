package io.kmp.taskmanager.sample

import platform.UIKit.UIDevice

/**
 * iOS-specific implementation of the Platform interface.
 */
class IOSPlatform: Platform {
    /**
     * Retrieves the iOS device's system name and version (e.g., "iOS 17.0").
     */
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

/**
 * Actual function to provide the platform information for iOS targets.
 *
 * @return An instance of IOSPlatform.
 */
actual fun getPlatform(): Platform = IOSPlatform()