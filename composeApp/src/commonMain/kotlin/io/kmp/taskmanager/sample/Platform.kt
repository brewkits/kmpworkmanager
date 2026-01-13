package io.kmp.taskmanager.sample

/**
 * Interface representing the current operating platform.
 */
interface Platform {
    /** The name and version of the operating system (e.g., "Android 14", "iOS 17.0"). */
    val name: String
}

/**
 * Expected function declaration to retrieve the platform information.
 * The actual implementation is provided by platform-specific modules.
 *
 * @return An object implementing the Platform interface.
 */
expect fun getPlatform(): Platform