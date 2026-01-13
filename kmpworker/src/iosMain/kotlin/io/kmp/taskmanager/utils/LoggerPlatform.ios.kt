package io.kmp.taskmanager.utils

import platform.Foundation.NSLog

/**
 * iOS implementation of Logger using NSLog for proper Xcode console output
 */
internal actual object LoggerPlatform {
    actual fun log(level: Logger.Level, message: String) {
        // NSLog automatically adds timestamp and process info
        NSLog(message)
    }
}
