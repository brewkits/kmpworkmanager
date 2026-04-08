@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import platform.Foundation.*

/**
 * Extension for test-only utilities to keep IosFileStorage clean.
 */
fun IosFileStorage.deleteMaintenanceTimestampForTesting() {
    val fileManager = NSFileManager.defaultManager
    // Extract url from the private property of IosFileStorage via reflection or path recreation
    // Since we cannot access it directly, we simulate the URL behavior
    val url = this.maintenanceTimestampURL
    if (url.path != null && fileManager.fileExistsAtPath(url.path!!)) {
        fileManager.removeItemAtURL(url, error = null)
    }
}
