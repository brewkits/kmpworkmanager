@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import platform.Foundation.*

/**
 * Extension for test-only utilities to keep IosFileStorage clean.
 */
fun IosFileStorage.deleteMaintenanceTimestampForTesting() {
    val fileManager = NSFileManager.defaultManager
    // Lấy url từ thuộc tính private của IosFileStorage bằng cách giả lập (hoặc access nếu public)
    // Vì tôi không thể access private, tôi sẽ tái tạo logic URL
    val baseDir = platform.Foundation.NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, inDomains = NSUserDomainMask).first() as NSURL
    val url = baseDir.URLByAppendingPathComponent("dev.brewkits.kmpworkmanager/last_maintenance_timestamp.txt", isDirectory = false)
    if (url != null && fileManager.fileExistsAtPath(url.path!!)) {
        fileManager.removeItemAtURL(url, error = null)
    }
}
